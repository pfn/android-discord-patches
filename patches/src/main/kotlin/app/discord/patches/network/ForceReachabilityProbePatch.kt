package app.discord.patches.network

import app.discord.patches.banner.DiscordConstants
import app.discord.patches.hermes.HermesBundle
import app.discord.patches.hermes.HermesBundle.Instr
import app.discord.patches.hermes.fail
import app.morphe.patcher.patch.resourcePatch

/**
 * Arms NetInfo's active reachability probe loop, which Discord's build leaves dead.
 *
 * The wedge behind "Waiting for network..." that only a force-kill clears: Discord's
 * connectivity gate (utils/native/NetworkUtils.tsx) mirrors NetInfo's native
 * isInternetReachable flag with no polling and no recheck - if the app misses the
 * "back online" event (5G idle/radio transitions fire these constantly), the flag
 * latches false forever: gateway won't reconnect, stores won't fetch, banner stays.
 *
 * NetInfo's InternetReachability.update() has the fix built in but branched off: when
 * useNativeReachability is true it just copies the native flag and returns; the other
 * path (_setExpectsConnection) runs a fetch probe (generate_204) that marks reachable
 * on success, retries every reachabilityShortTimeout (5s) while it fails, and rechecks
 * every reachabilityLongTimeout (60s) while it succeeds - the condition cannot latch.
 *
 * This patch flips that branch (JmpTrue -> JmpFalse, same Addr8 encoding, one byte) so
 * update() always takes the probing path. Every native connectivity event then triggers
 * a probe, so Discord dismisses the disconnected state itself as fast as the network
 * actually allows.
 *
 * Self-locating: 'useNativeReachability' has exactly one referencing function in the
 * bundle; inside it, the config property read (GetById with the name sid) is followed
 * within 2 instructions by the conditional branch. Everything is verified by re-parsing
 * before write; anything unrecognized fails loudly naming what to update.
 *
 * Gate history: 354.12 - Stable.
 */
val forceReachabilityProbePatch = resourcePatch(
    name = "Aggressive network recovery",
    description = "Discord actively probes the network every few seconds instead of latching " +
        "an offline state until force-killed (fixes stuck \"Waiting for network...\" on mobile).",
    default = true,
) {
    compatibleWith(DiscordConstants.COMPATIBILITY_DISCORD)

    execute {
        val bundle = get("assets/index.android.bundle", true)
        val bytes = bundle.readBytes()
        val hbc = HermesBundle(bytes)

        val name = "useNativeReachability"
        val sid = hbc.stringId(name)
        if (sid < 0) {
            fail("Aggressive network recovery FAILED: string '$name' missing from the bundle " +
                "string table - update ForceReachabilityProbePatch.kt")
        }
        val refs = hbc.findersOf(sid).filter { hbc.refsString(hbc.decode(it), sid) }
        if (refs.size != 1) {
            fail("Aggressive network recovery FAILED: ${refs.size} functions reference " +
                "'$name' (want exactly 1: NetInfo's InternetReachability.update) - " +
                "update ForceReachabilityProbePatch.kt")
        }
        val fid = refs[0]
        val ins = hbc.decode(fid)

        val jmpTrueOp = OPCODE_JMP_TRUE
        val jmpFalseOp = OPCODE_JMP_FALSE
        var branch: Instr? = null
        for ((idx, i) in ins.withIndex()) {
            if (i.op.name != "GetById" || i.vals[3] != sid) continue
            for (j in 1..2) {
                val j2 = ins.getOrNull(idx + j) ?: break
                if (j2.op.name == "JmpTrue") { branch = j2; break }
                if (j2.op.name == "JmpTrueLong") {
                    fail("Aggressive network recovery FAILED: branch after '$name' is " +
                        "JmpTrueLong at ${j2.off}, expected the 8-bit JmpTrue shape - " +
                        "update ForceReachabilityProbePatch.kt")
                }
            }
            if (branch != null) break
        }
        val jump = branch ?: fail(
            "Aggressive network recovery FAILED: no JmpTrue branch follows the '$name' read " +
                "in fid $fid - update ForceReachabilityProbePatch.kt"
        )
        if (bytes[jump.off].toInt() and 0xFF != jmpTrueOp) {
            fail("Aggressive network recovery FAILED: opcode at ${jump.off} is " +
                "0x${bytes[jump.off].toString(16)}, expected JmpTrue (0x${jmpTrueOp.toString(16)}) - " +
                "update ForceReachabilityProbePatch.kt")
        }

        // One byte: JmpTrue -> JmpFalse. Same Addr8 operand, so the jump target is unchanged;
        // only the condition inverts (take the _setExpectsConnection probe path).
        bytes[jump.off] = jmpFalseOp.toByte()

        // Self-check: re-parse; the branch must now be JmpFalse with the identical target.
        val verify = HermesBundle(bytes)
        val after = verify.decode(fid).singleOrNull {
            it.off == jump.off && (it.op.name == "JmpTrue" || it.op.name == "JmpFalse")
        }
        if (after == null || after.op.name != "JmpFalse" || after.vals[0] != jump.vals[0]) {
            fail("Aggressive network recovery FAILED: post-patch verification mismatch at " +
                "${jump.off} - update ForceReachabilityProbePatch.kt")
        }

        bundle.writeBytes(bytes)
        println("Aggressive network recovery: InternetReachability.update (fid $fid) branch at " +
            "${jump.off} flipped to always probe; retry ${'<'}5s while unreachable")
    }
}

// HBC98 opcode bytes (scripts/hbc/ops.txt): JmpTrue = 176, JmpFalse = 178. Both are
// Addr8 Reg8, so the flip preserves the operand encoding exactly.
private const val OPCODE_JMP_TRUE = 176
private const val OPCODE_JMP_FALSE = 178
