package org.ungoogled.patches.samsungkeyboard.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val DOWNLOADS = "Lorg/ungoogled/samsungkeyboard/Downloads;"
private const val DOWNLOAD_MANAGER = "Lcom/samsung/android/honeyboard/base/languagedownload/lmdownload/LanguageDownloadManager;"
private const val LANGUAGE = "Lcom/samsung/android/honeyboard/base/languagepack/language/Language;"

/** Logged by the keyboard's request to Samsung's Galaxy Store server when it cannot close the answer's stream. */
private const val FETCH_LOG = "failed to close session"

/** Logged by a language download that ends without a download address from Samsung. */
private const val FILE_INFO_ERROR = "LM_DOWNLOAD_FILE_INFO_ERROR"

private fun Method.strings() =
    implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
        ?: emptyList()

/**
 * Samsung's server refuses language downloads to phones it does not know (every phone but a Galaxy), and the keyboard
 * then ended the download silently and, unless its Languages page was open, left the language "downloading" for good.
 * Each answer from the server goes to Downloads; a download that ends in error is cleared as the Languages page clears
 * it, and the user is told why when Samsung refused the phone.
 */
internal val languageDownloadsPatch = bytecodePatch(
    description = "Says why language downloads fail off Galaxy phones.",
) {
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        // The request: String(String url), the server's answer as text ("" when the request failed). Static in the
        // ...300 builds, an instance method in the ...400 ones.
        val fetches = mutableListOf<Method>()
        classDefForEach { c ->
            c.methods.filterTo(fetches) { m ->
                m.returnType == "Ljava/lang/String;" && m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                    FETCH_LOG in m.strings()
            }
        }
        val fetch = fetches.singleOrNull() ?: throw PatchException("Galaxy Store request not found (${fetches.size} candidates)")
        val key = "${fetch.definingClass}->${fetch.name}(Ljava/lang/String;)Ljava/lang/String;"
        fun Any.calls() = this is ReferenceInstruction && (reference as? MethodReference)?.let { r ->
            "${r.definingClass}->${r.name}(${r.parameterTypes.joinToString("")})${r.returnType}" == key
        } == true

        // Every use of the answer: right after the call's result is taken.
        val sites = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            c.methods.filter { m ->
                m.implementation?.instructions?.any { it.calls() } == true
            }.forEach { sites += c.type to it }
        }
        var answers = 0
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            for (index in instructions.indices.reversed()) {
                if (!instructions[index].calls()) continue
                val result = instructions.getOrNull(index + 1)
                if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) continue
                // The range form, since the result's register may be past v15.
                val register = "v${(result as OneRegisterInstruction).registerA}"
                mutable.addInstruction(index + 2, "invoke-static/range { $register .. $register }, $DOWNLOADS->answer(Ljava/lang/String;)V")
                answers++
            }
        }
        // Measured against 5.9.40.48 and 5.9.40.12: the download, the update check and the update check for several
        // languages.
        if (answers == 0) throw PatchException("no use of the Galaxy Store answer found")

        // The download's end in error: (int code, String detail) in the class that names its codes.
        val ends = mutableListOf<Method>()
        classDefForEach { c ->
            c.methods.filterTo(ends) { m ->
                m.returnType == "V" && m.parameterTypes.map(CharSequence::toString) == listOf("I", "Ljava/lang/String;") &&
                    FILE_INFO_ERROR in m.strings()
            }
        }
        val end = ends.singleOrNull() ?: throw PatchException("language download error handler not found (${ends.size} candidates)")
        if (AccessFlags.STATIC.isSet(end.accessFlags)) throw PatchException("language download error handler is static")
        // The download itself (it holds the language) and the code: this and the int, consecutive.
        mutableClassDefBy(end.definingClass).methods.first {
            it.name == end.name && it.parameterTypes == end.parameterTypes && it.returnType == end.returnType
        }.addInstruction(0, "invoke-static/range { p0 .. p1 }, $DOWNLOADS->failed(Ljava/lang/Object;I)V")

        // The list of running downloads the settings pages show: its method that ends one, by language, is the one that
        // tells the download manager both to fail and to cancel it. Each list hands itself and that method to Downloads.
        val ending = mutableListOf<Method>()
        classDefForEach { c ->
            c.methods.filterTo(ending) { m ->
                val calls = m.implementation?.instructions?.mapNotNull {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { r -> r.definingClass == DOWNLOAD_MANAGER }?.name
                } ?: emptyList()
                m.returnType == "V" && m.parameterTypes.map(CharSequence::toString) == listOf(LANGUAGE, "Z") &&
                    "failToDownload" in calls && "cancelDownload" in calls
            }
        }
        val ender = ending.singleOrNull() ?: throw PatchException("running downloads list not found (${ending.size} candidates)")
        var lists = 0
        for (constructor in mutableClassDefBy(ender.definingClass).methods.filter { it.name == "<init>" }) {
            val implementation = constructor.implementation ?: continue
            // Two registers that are free once the constructor returns.
            if (implementation.registerCount - constructor.parameterTypes.size - 1 < 2) {
                throw PatchException("no free registers in ${ender.definingClass} constructor")
            }
            val returns = implementation.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            for (index in returns.reversed()) {
                // Replaced in place, so a branch to the return runs it too.
                constructor.replaceInstruction(index, "move-object/from16 v0, p0")
                constructor.addInstructions(
                    index + 1,
                    """
                        const-string v1, "${ender.name}"
                        invoke-static/range { v0 .. v1 }, $DOWNLOADS->downloads(Ljava/lang/Object;Ljava/lang/String;)V
                        return-void
                    """,
                )
                lists++
            }
        }
        if (lists == 0) throw PatchException("${ender.definingClass} has no constructor")

        java.util.logging.Logger.getLogger("SamsungKeyboard").info("Galaxy Store answers: $answers; download lists: $lists")
    }
}
