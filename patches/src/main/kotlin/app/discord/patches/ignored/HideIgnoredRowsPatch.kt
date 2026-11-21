package app.discord.patches.ignored

import app.discord.patches.banner.DiscordConstants
import app.discord.patches.hermes.HermesBundle
import app.discord.patches.hermes.fail
import app.morphe.patcher.patch.resourcePatch

/**
 * Removes the "N blocked messages" / "N ignored messages" collapsed rows from chat by making
 * Discord's own gate report that the channel has none.
 *
 * Discord's relationship store exposes hasBlockedOrIgnoredUserIds (its only caller returns the
 * negation to decide whether the collapsed rows render at all). Patching the method to return
 * false hides the rows; the ignored/blocked messages themselves are never rendered individually
 * by Discord (only the collapsed row represents them), so nothing else leaks.
 *
 * Self-locating: find the store method by its unique property name in the bundle's string table,
 * through the module fragment that assigns it (PutByIdStrictLong of the name sid), and overwrite
 * the assigned closure's head with `LoadConstFalse r0; Ret r0`. Anything unrecognized fails
 * loudly with the update point named.
 *
 * Gate history: 354.12 - Stable (sid verified by this patch's first release). The sid is looked
 * up by name at patch time, so renames of unrelated strings don't move it.
 */
val hideIgnoredRowsPatch = resourcePatch(
    name = "Hide blocked or ignored message rows",
    description = "Removes the \"blocked messages\" and \"ignored messages\" rows from chat.",
    default = true,
) {
    compatibleWith(DiscordConstants.COMPATIBILITY_DISCORD)

    execute {
        val bundle = get("assets/index.android.bundle", true)
        val bytes = bundle.readBytes()
        val hbc = HermesBundle(bytes)

        val name = "hasBlockedOrIgnoredUserIds"
        val sid = hbc.stringId(name)
        if (sid < 0) {
            fail("Hide ignored rows FAILED: string '$name' missing from the bundle string table - " +
                "update the anchor in HideIgnoredRowsPatch.kt")
        }
        val refs = hbc.findersOf(sid)
        if (refs.size != 2) {
            fail("Hide ignored rows FAILED: $refs functions reference '$name' (want exactly 2: the " +
                "defining fragment and the caller) - update HideIgnoredRowsPatch.kt")
        }

        // The definer fragment contains a PutByIdStrictLong whose last operand is the name sid;
        // the CreateClosureLongIndex immediately before it carries the method's function index.
        var factory = -1
        for (fid in refs) {
            val ins = hbc.decode(fid)
            for ((idx, i) in ins.withIndex()) {
                if (i.op.name != "PutByIdStrictLong" && i.op.name != "PutById") continue
                if (i.vals.last() != sid) continue
                val prev = ins.getOrNull(idx - 1)
                if (prev == null || prev.op.name != "CreateClosureLongIndex") {
                    fail("Hide ignored rows FAILED: property assignment for '$name' is not preceded " +
                        "by CreateClosureLongIndex at fid $fid - update HideIgnoredRowsPatch.kt")
                }
                factory = prev.vals.last()
                break
            }
            if (factory >= 0) break
        }
        if (factory < 0) {
            fail("Hide ignored rows FAILED: no property assignment for '$name' found - update " +
                "HideIgnoredRowsPatch.kt")
        }

        val method = hbc.decode(factory)
        val opNames = method.map { it.op.name }
        if (method.size > 16 || !opNames.contains("CreateClosureLongIndex") || opNames.last() != "Ret") {
            fail("Hide ignored rows FAILED: method body at fid $factory is ${method.size} instrs " +
                "(${opNames.take(6)}...) - not the expected delegation wrapper - update HideIgnoredRowsPatch.kt")
        }

        // The method returns the collection of blocked/ignored user ids for the channel (callers
        // coerce it with Not, read .length, iterate or filter it). Return an empty array: every
        // consumer pattern then behaves as "nothing blocked or ignored" and the collapsed rows
        // never render. NewArray r0 (2B) + Ret r0 (2B).
        val at = hbc.funcs[factory].off
        val edit = byteArrayOf(8.toByte(), 0, 118.toByte(), 0)
        edit.forEachIndexed { i, byte -> bytes[at + i] = byte }
        bundle.writeBytes(bytes)
        println("Hide ignored rows: $name (fid $factory) returns empty array, patched at bundle offset $at")
    }
}
