"""Simulate the Kotlin HideIgnoredRowsPatch algorithm against the real 354.12 bundle.

Discovers the two row-group generators by string-reference signature, locates the
classification branches and the group-row pushes, applies the edits, and verifies
by re-decoding the patched bundle.
"""
import struct
from hbc import HBC, OPINFO, STRING_OPS

BUNDLE = '/home/user/morphe-discord/work-bundle/assets/index.android.bundle'

h = HBC(BUNDLE)

def sid_of(name):
    for i in range(h.h['stringCount']):
        if h.string(i) == name:
            return i
    return -1

names = ['blocked', 'ignored', 'BLOCKED_GROUP', 'IGNORED_GROUP',
         'HIDDEN_SUSPENDED_USER', 'push', 'revealedMessageId']
sids = {n: sid_of(n) for n in names}
print('sids:', sids)
assert all(v >= 0 for v in sids.values())

# ---- function discovery (mirrors findersOf + refsString) ----
def refs(fid, sid):
    f = h.func(fid); pc = f['offset']; end = pc + f['size']
    while pc < end:
        name, offs, ln = OPINFO[h.b[pc]]
        ks = STRING_OPS.get(name)
        if ks:
            for k in ks:
                if struct.unpack_from(offs[k][1], h.b, pc + offs[k][0])[0] == sid:
                    return True
        pc += ln
    return False

def find(required):
    cands = set()
    for i in range(h.h['functionCount']):
        f = h.func(i); pc = f['offset']; end = pc + f['size']
        while pc < end:
            name, offs, ln = OPINFO[h.b[pc]]
            ks = STRING_OPS.get(name)
            if ks:
                for k in ks:
                    if struct.unpack_from(offs[k][1], h.b, pc + offs[k][0])[0] in required:
                        cands.add(i)
            pc += ln
    return [f for f in sorted(cands) if all(refs(f, s) for s in required.values())]

f1c = find({sids['HIDDEN_SUSPENDED_USER']: sids['HIDDEN_SUSPENDED_USER'],
            sids['blocked']: sids['blocked'], sids['ignored']: sids['ignored'],
            sids['BLOCKED_GROUP']: sids['BLOCKED_GROUP'], sids['IGNORED_GROUP']: sids['IGNORED_GROUP']})
print('F1 candidates:', f1c)
assert f1c == [106650], f1c

f2c = find({sids['revealedMessageId']: sids['revealedMessageId'],
            sids['blocked']: sids['blocked'], sids['ignored']: sids['ignored'],
            sids['BLOCKED_GROUP']: sids['BLOCKED_GROUP'], sids['IGNORED_GROUP']: sids['IGNORED_GROUP']})
f2c = [f for f in f2c if f != 106650]
print('F2 candidates:', f2c)
assert f2c == [128001], f2c

# ---- branch pattern discovery ----
def decode(fid):
    out = []
    f = h.func(fid); pc = f['offset']; end = pc + f['size']
    while pc < end:
        name, offs, ln = OPINFO[h.b[pc]]
        vals = [struct.unpack_from(offs[k][1], h.b, pc + offs[k][0])[0] for k in range(len(offs))]
        out.append((pc - f['offset'], name, vals, ln))
        pc += ln
    return out

def find_jumps(ins, sid):
    """GetById(sid) followed within 2 instrs by JmpTrueLong or JmpTrue -> [(pc, opname, rel_target, reg)]"""
    out = []
    for idx, (pc, name, vals, ln) in enumerate(ins):
        if name != 'GetById' or vals[3] != sid:
            continue
        for j in (1, 2):
            if idx + j >= len(ins):
                break
            pc2, name2, vals2, _ = ins[idx + j]
            if name2 in ('JmpTrueLong', 'JmpTrue'):
                out.append((pc2, name2, vals2[0], vals2[1], pc))
                break
    return out

def first_jmp_after(ins, from_pc):
    for pc, name, vals, ln in ins:
        if pc >= from_pc and name == 'JmpLong':
            return pc + vals[0]
    raise AssertionError('no JmpLong after %d' % from_pc)

def first_push_call2_after(ins, from_pc, push_sid):
    for idx, (pc, name, vals, ln) in enumerate(ins):
        if pc >= from_pc and name == 'GetByIdShort' and vals[3] == push_sid:
            pc2, name2, vals2, _ = ins[idx + 1]
            assert name2 == 'Call2' and vals2[0] == vals2[1] and vals2[2] == vals[1], (pc, vals, pc2, vals2)
            return pc2, vals2
    raise AssertionError('no push after %d' % from_pc)

edits = []  # (absolute_file_offset, old_bytes, new_bytes, description)

for fid, label in [(106650, 'F1/channel-stream'), (128001, 'F2/chat-preview')]:
    ins = decode(fid)
    j_blocked = find_jumps(ins, sids['blocked'])
    j_ignored = find_jumps(ins, sids['ignored'])
    print(f'{label} blocked jumps:', j_blocked)
    print(f'{label} ignored jumps:', j_ignored)
    assert len(j_blocked) == 1 and len(j_ignored) == 1

    (pb, nb, tb, rb, _) = j_blocked[0]
    (pi, ni, ti, ri, _) = j_ignored[0]
    t_blocked_abs = pb + tb
    t_ignored_abs = pi + ti
    print(f'  branch starts: blocked@{t_blocked_abs} ignored@{t_ignored_abs}')

    tail = first_jmp_after(ins, t_ignored_abs)
    print(f'  tail (common path after group branches): {tail}')

    def retarget(jump, desc):
        pc_j, name_j, t_j, reg_j, _ = jump
        assert name_j == 'JmpTrueLong', (desc, name_j)
        # operand starts one byte past the opcode
        edits.append((h.func(fid)['offset'] + pc_j + 1, struct.pack('<i', t_j),
                      struct.pack('<i', tail - pc_j), desc))

    if ni == 'JmpTrueLong':
        retarget(j_ignored[0], f'{label}: ignored JmpTrueLong -> tail')
    else:
        # 8-bit jump can't reach the tail: neutralize the group-row push instead.
        pc2, vals2 = first_push_call2_after(ins, t_ignored_abs, sids['push'])
        at = h.func(fid)['offset'] + pc2
        old = h.b[at:at + 5]
        # Mov dest, this ; LoadConstUndefined dest  (5 bytes total)
        new = bytes([16, vals2[0], vals2[2]]) + bytes([147, vals2[0]])
        edits.append((at, old, new, f'{label}: ignored-branch push Call2 -> dead writes'))
    retarget(j_blocked[0], f'{label}: blocked JmpTrueLong -> tail')

print()
for at, old, new, desc in edits:
    print(f'EDIT @{at} (0x{at:x}): {old.hex()} -> {new.hex()}  # {desc}')

# ---- apply + verify ----
data = bytearray(h.b[:])  # full bundle image (mmap -> copy)
for at, old, new, desc in edits:
    assert bytes(data[at:at + len(old)]) == old, (at, desc)
    data[at:at + len(new)] = new

open('/tmp/patched.bundle', 'wb').write(data)
print('\npatched bundle written, re-decoding...')

h2 = HBC('/tmp/patched.bundle')
for fid, label in [(106650, 'F1'), (128001, 'F2')]:
    ins2 = []
    f = h2.func(fid); pc = f['offset']; end = pc + f['size']
    while pc < end:
        name, offs, ln = OPINFO[h2.b[pc]]
        vals = [struct.unpack_from(offs[k][1], h2.b, pc + offs[k][0])[0] for k in range(len(offs))]
        ins2.append((pc - f['offset'], name, vals, ln))
        pc += ln
    for pc, name, vals, ln in ins2:
        if name in ('JmpTrueLong', 'JmpTrue') and (pc, name) in [(p, n) for p, n, v, r, _ in
                find_jumps(ins2, sids['blocked']) + find_jumps(ins2, sids['ignored'])]:
            print(f'{label} @{pc} {name} target={pc + vals[0]} (was branch entry)')
    # sanity: function still decodes to same length and last instr is Ret
    print(f'{label}: {len(ins2)} instrs, last = {ins2[-1][1]} @{ins2[-1][0]}')
print('\nSIMULATION OK')
