package app.discord.patches.ignored

import app.discord.patches.banner.DiscordConstants
import app.discord.patches.hermes.HermesBundle
import app.discord.patches.hermes.HermesBundle.Instr
import app.discord.patches.hermes.OPCODE_TABLE
import app.discord.patches.hermes.fail
import app.morphe.patcher.patch.resourcePatch

/**
 * Hides messages from blocked and ignored users completely: no content, no collapsed
 * "N blocked/ignored messages" divider row, nothing.
 *
 * How Discord renders them on 354.12: message records carry `blocked` / `ignored` flags
 * (set by applyRelationshipFlags at record creation). The row builders read those flags
 * right before the per-message loop and route the whole message group to a BLOCKED_GROUP /
 * IGNORED_GROUP row - the collapsed divider. That row is the ONLY surface on which
 * blocked/ignored content ever reaches the screen, so suppressing it suppresses everything.
 *
 * Patch, per classification site:
 *  - the `if (message.blocked)` / `if (message.ignored)` branch entries (JmpTrueLong) are
 *    retargeted to the common tail after the group-row branches, so a blocked or ignored
 *    message group produces zero rows (channel stream builder);
 *  - the chat-preview builder's ignored branch uses an 8-bit JmpTrue that cannot reach the
 *    tail, so there the group-row `rows.push(row)` Call2 is overwritten with dead register
 *    writes (Mov + LoadConstUndefined) instead; its blocked branch is retargeted normally.
 *
 * Self-locating: the two builders are found by their unique string-reference signature
 * (channel stream: HIDDEN_SUSPENDED_USER + blocked + ignored + BLOCKED_GROUP + IGNORED_GROUP;
 * chat preview: revealedMessageId + the same four). Every structural assumption is checked,
 * the edits are verified by re-parsing the bundle, and anything unrecognized fails loudly
 * naming what to update.
 *
 * Gate history: 354.12 - Stable (all anchors verified against the shipped bundle).
 * Note: the earlier approach (neutering the relationship store's hasBlockedOrIgnoredUserIds)
 * was verified INEFFECTIVE on 354.12 - the dividers render through the path patched here.
 */
val hideIgnoredRowsPatch = resourcePatch(
    name = "Hide blocked or ignored message rows",
    description = "Hides messages from blocked and ignored users entirely - no content, no divider.",
    default = true,
) {
    compatibleWith(DiscordConstants.COMPATIBILITY_DISCORD)

    execute {
        val bundle = get("assets/index.android.bundle", true)
        val bytes = bundle.readBytes()
        val hbc = HermesBundle(bytes)

        val sidByName = listOf(
            "blocked", "ignored", "BLOCKED_GROUP", "IGNORED_GROUP",
            "HIDDEN_SUSPENDED_USER", "push", "revealedMessageId",
        ).associateWith { name ->
            val sid = hbc.stringId(name)
            if (sid < 0) {
                fail("Hide ignored rows FAILED: string '$name' missing from the bundle string " +
                    "table - update the anchors in HideIgnoredRowsPatch.kt")
            }
            sid
        }
        val sidBlocked = sidByName.getValue("blocked")
        val sidIgnored = sidByName.getValue("ignored")
        val sidPush = sidByName.getValue("push")

        // Locate one function by its unique string-reference signature.
        fun locate(name: String, signature: Map<String, Int>): Int {
            val anchor = signature.values.first()
            val cands = hbc.findersOf(anchor).filter { fid ->
                val ins = hbc.decode(fid)
                signature.values.all { sid -> hbc.refsString(ins, sid) }
            }
            if (cands.size != 1) {
                fail("Hide ignored rows FAILED: ${cands.size} functions match the $name " +
                    "signature (want exactly 1) - update HideIgnoredRowsPatch.kt")
            }
            return cands[0]
        }

        val streamFid = locate(
            "channel stream",
            mapOf(
                "HIDDEN_SUSPENDED_USER" to sidByName.getValue("HIDDEN_SUSPENDED_USER"),
                "blocked" to sidBlocked,
                "ignored" to sidIgnored,
                "BLOCKED_GROUP" to sidByName.getValue("BLOCKED_GROUP"),
                "IGNORED_GROUP" to sidByName.getValue("IGNORED_GROUP"),
            ),
        )
        val previewFid = locate(
            "chat preview",
            mapOf(
                "revealedMessageId" to sidByName.getValue("revealedMessageId"),
                "blocked" to sidBlocked,
                "ignored" to sidIgnored,
                "BLOCKED_GROUP" to sidByName.getValue("BLOCKED_GROUP"),
                "IGNORED_GROUP" to sidByName.getValue("IGNORED_GROUP"),
            ),
        )
        if (previewFid == streamFid) {
            fail("Hide ignored rows FAILED: stream and preview builders resolved to the same " +
                "function (fid $streamFid) - update HideIgnoredRowsPatch.kt")
        }

        // The classification test: GetById(<flag>) followed within 2 instructions by a
        // conditional jump into the group-row branch.
        fun branchJump(ins: List<Instr>, sid: Int, label: String): Instr {
            for ((idx, i) in ins.withIndex()) {
                if (i.op.name != "GetById" || i.vals[3] != sid) continue
                for (j in 1..2) {
                    val j2 = ins.getOrNull(idx + j) ?: break
                    if (j2.op.name == "JmpTrueLong" || j2.op.name == "JmpTrue") return j2
                }
            }
            fail("Hide ignored rows FAILED: no '$label' classification branch found - " +
                "update HideIgnoredRowsPatch.kt")
        }

        // Common tail all group-row branches converge to: the first forward JmpLong after a
        // branch entry. Both branches here are straight-line (verified against 354.12).
        fun tailAfter(ins: List<Instr>, branchEntry: Int, label: String): Int {
            for (i in ins) {
                if (i.op.name != "JmpLong" || i.off <= branchEntry) continue
                val tail = i.off + i.vals[0]
                if (tail - branchEntry !in 16..1200) {
                    fail("Hide ignored rows FAILED: $label tail at $tail is ${tail - branchEntry} " +
                        "bytes past the branch entry - unexpected shape, update HideIgnoredRowsPatch.kt")
                }
                return tail
            }
            fail("Hide ignored rows FAILED: no branch-terminating JmpLong after $label group " +
                "branch (entry $branchEntry) - update HideIgnoredRowsPatch.kt")
        }

        fun patchJmpTrueLong(jump: Instr, label: String, tail: Int) {
            if (jump.op.name != "JmpTrueLong") {
                fail("Hide ignored rows FAILED: $label branch jump is ${jump.op.name}, expected " +
                    "JmpTrueLong - update HideIgnoredRowsPatch.kt")
            }
            val rel = tail - jump.off
            for (k in 0 until 4) bytes[jump.off + 1 + k] = (rel ushr (8 * k)).toByte()
            println("Hide ignored rows: $label branch retargeted ${jump.off} -> tail $tail")
        }

        // Chat-preview ignored branch: 8-bit JmpTrue cannot reach the tail; neutralize the
        // group-row push instead. GetByIdShort('push') + Call2(rows.push(row)) becomes
        // Mov + LoadConstUndefined on the call's destination register (dead writes; the
        // register is reassigned before its next read in 354.12).
        fun neutralizePush(ins: List<Instr>, branchEntry: Int, label: String) {
            val movOp = OPCODE_TABLE.indexOfFirst { it.name == "Mov" }
            val undefOp = OPCODE_TABLE.indexOfFirst { it.name == "LoadConstUndefined" }
            if (movOp < 0 || undefOp < 0) {
                fail("Hide ignored rows FAILED: Mov/LoadConstUndefined missing from OPCODE_TABLE")
            }
            for ((idx, i) in ins.withIndex()) {
                if (i.off <= branchEntry || i.op.name != "GetByIdShort" || i.vals[3] != sidPush) continue
                val call = ins.getOrNull(idx + 1)
                if (call == null || call.op.name != "Call2" || call.vals[0] != i.vals[0] ||
                    call.vals[2] != i.vals[1]
                ) {
                    fail("Hide ignored rows FAILED: $label group-row push is not " +
                        "GetByIdShort('push')+Call2 at ${i.off} - update HideIgnoredRowsPatch.kt")
                }
                val dest = call.vals[0]
                val edit = byteArrayOf(
                    movOp.toByte(), dest.toByte(), call.vals[2].toByte(),
                    undefOp.toByte(), dest.toByte(),
                )
                edit.forEachIndexed { k, b -> bytes[call.off + k] = b }
                println("Hide ignored rows: $label group-row push neutralized at ${call.off}")
                return
            }
            fail("Hide ignored rows FAILED: no 'push' call after $label group branch " +
                "(entry $branchEntry) - update HideIgnoredRowsPatch.kt")
        }

        val tails = mutableMapOf<Int, Int>()
        for ((fid, label) in listOf(streamFid to "channel stream", previewFid to "chat preview")) {
            val ins = hbc.decode(fid)
            val funcEnd = hbc.funcs[fid].off + hbc.funcs[fid].size

            val jBlocked = branchJump(ins, sidBlocked, "$label blocked")
            val jIgnored = branchJump(ins, sidIgnored, "$label ignored")
            val entryIgnored = jIgnored.off + jIgnored.vals[0]
            val tail = tailAfter(ins, entryIgnored, "$label ignored-group")
            if (tail >= funcEnd) {
                fail("Hide ignored rows FAILED: $label tail $tail outside function (ends $funcEnd)")
            }
            tails[fid] = tail

            patchJmpTrueLong(jBlocked, "$label blocked", tail)
            if (jIgnored.op.name == "JmpTrueLong") {
                patchJmpTrueLong(jIgnored, "$label ignored", tail)
            } else {
                neutralizePush(ins, entryIgnored, label)
            }
        }

        // Self-check: re-parse the edited bundle. Every retargeted jump must land on the
        // (recomputed) tail, and no group-row push Call2 may remain inside a retargeted branch.
        val verify = HermesBundle(bytes)
        for ((fid, label) in listOf(streamFid to "channel stream", previewFid to "chat preview")) {
            val ins = verify.decode(fid)
            val jBlocked = branchJump(ins, sidBlocked, "$label blocked")
            val jIgnored = branchJump(ins, sidIgnored, "$label ignored")
            val tail = tails.getValue(fid)

            for ((jump, kind) in listOf(jBlocked to "blocked", jIgnored to "ignored")) {
                if (jump.op.name != "JmpTrueLong") continue
                if (jump.off + jump.vals[0] != tail) {
                    fail("Hide ignored rows FAILED: $label $kind branch still targets " +
                        "${jump.off + jump.vals[0]}, expected tail $tail")
                }
            }
            if (jIgnored.op.name != "JmpTrueLong") {
                // neutralized branch: the push call must be gone
                val entryIgnored = jIgnored.off + jIgnored.vals[0]
                for ((idx, i) in ins.withIndex()) {
                    if (i.off <= entryIgnored || i.op.name != "GetByIdShort" || i.vals[3] != sidPush) continue
                    if (i.off >= tail) break
                    val call = ins.getOrNull(idx + 1)
                    if (call != null && call.op.name == "Call2" && call.vals[0] == i.vals[0] &&
                        call.vals[2] == i.vals[1]
                    ) {
                        fail("Hide ignored rows FAILED: $label group-row push still present at " +
                            "${call.off} after neutralization")
                    }
                    break
                }
            }
        }

        bundle.writeBytes(bytes)
        println("Hide ignored rows: stream fid $streamFid, preview fid $previewFid patched")
    }
}
