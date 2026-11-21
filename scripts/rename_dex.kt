import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File

/**
 * Rewrites the kotlin.jvm.internal references in an extension dex to the app.venus.kshim runtime
 * (see extensions/i18n/src/main/kotlin/app/venus/kshim/Shim.kt), then fails if any kotlin
 * reference survived, so a new kotlin runtime dependency can never ship silently.
 */
fun main(a: Array<String>) {
    val from = "Lkotlin/jvm/internal/"
    val to = "Lapp/venus/kshim/"
    for (path in a) {
        val input = DexBackedDexFile.fromInputStream(null, File(path).inputStream().buffered())
        val module = object : RewriterModule() {
            override fun getTypeRewriter(rewriters: Rewriters): Rewriter<String> =
                Rewriter { value ->
                    if (value.startsWith(from)) to + value.substring(from.length) else value
                }
        }
        val rewritten = DexRewriter(module).dexFileRewriter.rewrite(input)
        DexPool.writeTo(path, rewritten)
        val dex = DexBackedDexFile.fromInputStream(null, File(path).inputStream().buffered())
        for (c in dex.classes) {
            if (c.type.startsWith("Lkotlin/")) throw IllegalStateException("$path defines " + c.type)
            for (m in c.methods) {
                val impl = m.implementation ?: continue
                for (i in impl.instructions) {
                    val s = (i as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)
                        ?.reference?.toString() ?: continue
                    if (s.startsWith("Lkotlin/")) throw IllegalStateException("$path still references " + s)
                }
            }
        }
        println("kshim rewrite ok: " + path)
    }
}
