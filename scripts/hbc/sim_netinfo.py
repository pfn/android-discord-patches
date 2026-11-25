"""Simulate the ForceOnline patch: flip NetInfo InternetReachability.update()'s
'useNativeReachability' fast-path branch (JmpTrue -> JmpFalse) so the active
reachability probe loop is always armed instead of passively mirroring the
native flag.

Target function is located by its unique 'useNativeReachability' string ref;
the branch is GetById(sid) followed by a JmpTrue within 2 instructions.
"""
import struct
from hbc import HBC, OPINFO, STRING_OPS

BUNDLE = '/home/user/morphe-discord/work-bundle/assets/index.android.bundle'
h = HBC(BUNDLE)

SID = None
for i in range(h.h['stringCount']):
    if h.string(i) == 'useNativeReachability':
        SID = i
        break
assert SID is not None
print('useNativeReachability sid:', SID)

# find referencing functions
refs = []
for fid in range(h.h['functionCount']):
    f = h.func(fid); pc = f['offset']; end = pc + f['size']
    while pc < end:
        name, offs, ln = OPINFO[h.b[pc]]
        ks = STRING_OPS.get(name)
        if ks:
            for k in ks:
                if struct.unpack_from(offs[k][1], h.b, pc + offs[k][0])[0] == SID:
                    refs.append(fid)
        pc += ln
print('referencing fids:', refs)
assert refs == [82874], refs

def decode(fid):
    out = []
    f = h.func(fid); pc = f['offset']; end = pc + f['size']
    while pc < end:
        name, offs, ln = OPINFO[h.b[pc]]
        vals = [struct.unpack_from(offs[k][1], h.b, pc + offs[k][0])[0] for k in range(len(offs))]
        out.append((pc - f['offset'], name, vals, ln))
        pc += ln
    return out

ins = decode(82874)
target = None
for idx, (pc, name, vals, ln) in enumerate(ins):
    if name == 'GetById' and vals[3] == SID:
        for j in (1, 2):
            pc2, name2, vals2, _ = ins[idx + j]
            if name2 in ('JmpTrue', 'JmpTrueLong'):
                target = (pc2, name2, vals2[0])
                break
        break
assert target is not None, 'branch not found'
pc_j, name_j, reg = target
print(f'branch: @{pc_j} {name_j} reg={reg}')

JMP_TRUE = 176
JMP_FALSE = 178
at = h.func(82874)['offset'] + pc_j
old_op = h.b[at]
assert old_op == JMP_TRUE, hex(old_op)

data = bytearray(h.b[:])
data[at] = JMP_FALSE
open('/tmp/patched-netinfo.bundle', 'wb').write(data)

# verify: re-decode, confirm JmpFalse with same target/reg, function still intact
h2 = HBC('/tmp/patched-netinfo.bundle')
ins2 = decode2 = []
f = h2.func(82874); pc = f['offset']; end = pc + f['size']
while pc < end:
    name, offs, ln = OPINFO[h2.b[pc]]
    vals = [struct.unpack_from(offs[k][1], h2.b, pc + offs[k][0])[0] for k in range(len(offs))]
    ins2.append((pc - f['offset'], name, vals, ln))
    pc += ln
found = [(p, n, v) for p, n, v, _ in ins2 if n in ('JmpTrue', 'JmpFalse') and p == pc_j]
print('after patch:', found)
assert found and found[0][1] == 'JmpFalse'
# whole function still decodes to same length and ends in Ret
assert ins2[-1][1] == 'Ret' and len(ins2) == len(ins)
print(f'length {len(ins2)} instrs unchanged, ends in Ret')
print('SIMULATION OK')
