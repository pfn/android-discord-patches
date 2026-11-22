package app.discord.patches.updater

import app.discord.patches.banner.DiscordConstants
import app.venus.patches.packagedDiscordBundle
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x

/**
 * Discord ships its own OTA updater (BundleUpdater): at startup
 * DCDReactNativeHost.getJSBundleFile() returns a downloaded bundle from
 * the app data dir when one exists, and only then do the Hermes patches
 * in the APK silently stop working (banner returns, composer gift
 * returns). Observed on device: both patches died together after an
 * update landed.
 *
 * This patch replaces that method's whole body with `return null`, which
 * is the updater's own documented fallback (existing :cond_0 path):
 * React Native then loads index.android.bundle from the APK assets —
 * the patched one. Verified per version by the CLI run plus smali
 * re-decode of the patched APK.
 *
 * On 354.12 and newer, DCDReactNativeHost and BundleUpdater are gone and
 * Discord folds the OTA-vs-packaged choice into obfuscated React host
 * setup methods; packagedDiscordBundle (below, as a dependency) patches
 * those sites structurally, so this patch pins every supported version
 * without the Conduit runtime.
 *
 * Trade-off: official JS updates stop arriving until the APK itself is
 * updated. That is inherent to Hermes patching; without this pin the
 * other patches cannot survive.
 */
val pinBuiltInBundlePatch = bytecodePatch(
    name = "Pin app to built-in bundle",
    description = "Forces Discord to always run the bundle inside the APK and ignore downloaded (OTA) updates, which would otherwise silently undo the other patches. Turn this off (or update the APK) to receive official updates.",
    default = true,
) {
    compatibleWith(DiscordConstants.COMPATIBILITY_DISCORD)
    dependsOn(packagedDiscordBundle)

    execute {
        val clazz = mutableClassDefByOrNull("Lcom/discord/bridge/DCDReactNativeHost;")
        if (clazz == null) {
            // 354.12 removed DCDReactNativeHost; the obfuscated loader sites are pinned by
            // packagedDiscordBundle (dependency above).
            println("Pin app to built-in bundle: no DCDReactNativeHost on this build; obfuscated loader sites pinned by packagedDiscordBundle.")
            return@execute
        }
        val method = clazz.methods.singleOrNull {
            it.name == "getJSBundleFile" &&
                it.returnType == "Ljava/lang/String;" &&
                it.parameters.isEmpty()
        } ?: error("DCDReactNativeHost.getJSBundleFile() not found - patch needs re-analysis.")

        val impl = checkNotNull(method.implementation) {
            "DCDReactNativeHost.getJSBundleFile() has no implementation - patch needs re-analysis."
        }
        while (impl.instructions.isNotEmpty()) impl.removeInstruction(0)
        impl.addInstruction(BuilderInstruction11n(Opcode.CONST_4, 0, 0))
        impl.addInstruction(BuilderInstruction11x(Opcode.RETURN_OBJECT, 0))
    }
}
