package org.ungoogled.patches.samsungkeyboard.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsung.convertSprDrawables
import org.ungoogled.patches.samsung.makeLibrariesOptional
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.samsung.redirectOneUiFrameworkLookups
import org.ungoogled.patches.samsung.redirectOneUiMembers
import org.ungoogled.patches.samsungkeyboard.SamsungKeyboardConstants.COMPATIBILITY_SAMSUNG_KEYBOARD
import org.w3c.dom.Element

private const val ONE_UI = "Lorg/ungoogled/samsungkeyboard/OneUi;"
private const val SERVICES = "Lorg/ungoogled/samsungkeyboard/Services;"
private const val LOCAL_CLIPBOARD = "Lorg/ungoogled/samsungkeyboard/LocalClipboard;"

/** Logged by the keyboard's check of which packages may read its clipboard history (Samsung's, by name). */
private const val CLIPBOARD_CALLER_REFUSED = ") can't access to clipboard."

/** Logged by the keyboard's check that a caller of its providers is signed by Samsung. */
private const val SIGNATURE_CHECK_FAILED = "certification failed"
private const val SIGNATURE_CHECK_PASSED = "valid signature : "
private const val KEYBOARD_SERVICE = "com.samsung.android.honeyboard.service.HoneyBoardService"
private const val KEYBOARD_SERVICE_TYPE = "Lcom/samsung/android/honeyboard/service/HoneyBoardService;"

private val log = java.util.logging.Logger.getLogger("SamsungKeyboard")

/**
 * One UI framework resources the keyboard looks up by name and uses unchecked, as "type/name" to their value off One
 * UI; each is added as "oneui_<name>", which OneUi.getIdentifier answers with (as in Clock, see
 * samsung/OneUiFrameworkResources). The settings page reserves room for One UI's task bar: there is none here.
 */
private val FRAMEWORK_RESOURCES = mapOf("dimen/task_bar_height" to "0dp")

/**
 * Samsung's own services the keyboard needs off One UI; Services answers them: stand-ins for the two it casts without
 * a null check, LocalClipboard for the clipboard service.
 */
private val SAMSUNG_SERVICES = setOf("sem_statusbar", "desktopmode", "semclipboard")
private val CONTEXTS = setOf(
    "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/view/ContextThemeWrapper;",
    "Landroid/app/Activity;", "Landroid/app/Application;", "Landroid/app/Service;", "Landroid/inputmethodservice/InputMethodService;",
)

/**
 * The manifest of the system keyboard: its shared user id (android.uid.honeyboard, Samsung's signature) and coreApp go;
 * Samsung's libraries it names are optional already.
 */
private val manifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val services = manifest.getElementsByTagName("service")
            val ime = (0 until services.length).any { (services.item(it) as Element).getAttribute("android:name") == KEYBOARD_SERVICE }
            if (!ime) throw PatchException("$KEYBOARD_SERVICE not in the manifest")
            manifest.documentElement.removeAttribute("android:sharedUserId")
            manifest.documentElement.removeAttribute("coreApp")
        }
        log.info("Required Samsung libraries made optional: ${makeLibrariesOptional()}")

        val types = FRAMEWORK_RESOURCES.keys.map { it.substringBefore('/') }.toSet()
        decode(*types.map { "res/values/${it}s.xml" }.toTypedArray())
        for (type in types) {
            document("res/values/${type}s.xml").use { xml ->
                for ((key, value) in FRAMEWORK_RESOURCES.filterKeys { it.startsWith("$type/") }) {
                    xml.documentElement.appendChild(xml.createElement(type).apply {
                        setAttribute("name", "oneui_" + key.substringAfter('/'))
                        textContent = value
                    })
                }
            }
        }
    }
}

/** Many icons are SPR files, Samsung's vector format that only One UI can draw (Manage input languages died on one). */
private val sprDrawablesPatch = resourcePatch(
    description = "Converts Samsung's SPR icons to vector drawables.",
) {
    execute {
        val converted = convertSprDrawables()
        log.info("SPR drawables: $converted")
        // Measured against 5.9.40.75: 78 files.
        if (converted < 30) throw PatchException("only $converted SPR drawables found")
    }
}

/**
 * Sends the keyboard's uses of One UI's additions to Android classes through OneUi (see redirectOneUiMembers; where
 * the phone has the member, OneUi calls it), its resource lookups by name through OneUi.getIdentifier, and the
 * service lookups of [SAMSUNG_SERVICES] through Services (with the clipboard history fed by LocalClipboard). Runs
 * after the system-access shims, which take One UI's per-user settings read themselves.
 */
private val oneUiMembersPatch = bytecodePatch(
    description = "Answers One UI's additions to Android classes where the phone has none.",
) {
    dependsOn(systemAccessPatch)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        val (calls, fields) = redirectOneUiMembers(ONE_UI, "Lorg/ungoogled/samsungkeyboard/Sem;")
        val lookups = redirectOneUiFrameworkLookups(ONE_UI)
        // The settings pages' windows ask for no layout limits, which off One UI loses them the status bar's inset, and
        // the keyboard's dialogs are system windows (input method dialogs): OneUi keeps the limits and attaches the
        // dialogs to the keyboard's window where the app may not use them.
        val windowFlags = redirectCalls(ONE_UI, mapOf("Landroid/view/Window;" to setOf("setFlags(II)V", "setType(I)V")))
        if (classDefByOrNull(KEYBOARD_SERVICE_TYPE) == null) throw PatchException("$KEYBOARD_SERVICE not found")
        mutableClassDefBy(KEYBOARD_SERVICE_TYPE).methods.firstOrNull { it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V" }
            ?.addInstructions(0, "invoke-static { p0 }, $ONE_UI->keyboardCreated(Landroid/inputmethodservice/InputMethodService;)V")
            ?: throw PatchException("$KEYBOARD_SERVICE.onCreate not found")

        // Only the methods that name one of the unchecked services: every other lookup stays as it is.
        fun Method.names() = implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string in SAMSUNG_SERVICES
        } ?: false
        fun Any.isLookup() = this is ReferenceInstruction &&
            (opcode == Opcode.INVOKE_VIRTUAL) && (reference as? MethodReference)?.let {
                it.definingClass in CONTEXTS && it.name == "getSystemService" && it.returnType == "Ljava/lang/Object;" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
            } == true
        val sites = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            c.methods.filter { it.names() }.forEach { sites += c.type to it }
        }
        var services = 0
        for ((type, method) in sites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            for (index in instructions.indices) {
                val insn = instructions[index]
                if (!insn.isLookup()) continue
                val i = insn as FiveRegisterInstruction
                mutable.replaceInstruction(
                    index,
                    "invoke-static { v${i.registerC}, v${i.registerD} }, " +
                        "$SERVICES->get(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/Object;",
                )
                services++
            }
        }

        // LocalClipboard adds copies to the keyboard's history through its clipboard provider, which only trusts callers
        // signed by Samsung: the keyboard's own calls are trusted too.
        val checks = mutableListOf<Method>()
        classDefForEach { c ->
            c.methods.filterTo(checks) { m ->
                m.returnType == "Z" && m.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "Ljava/lang/String;") &&
                    m.implementation?.instructions?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                        ?.let { SIGNATURE_CHECK_FAILED in it && SIGNATURE_CHECK_PASSED in it } == true
            }
        }
        // One copy per module (the clipboard's and the settings'); 5.9.40.75 has two.
        if (checks.isEmpty()) throw PatchException("provider signature check not found")
        for (check in checks) {
            mutableClassDefBy(check.definingClass).methods.first {
                it.name == check.name && it.parameterTypes == check.parameterTypes && it.returnType == check.returnType
            }.apply {
                val static = AccessFlags.STATIC.isSet(accessFlags)
                val (context, caller) = if (static) "p0" to "p1" else "p1" to "p2"
                if (implementation!!.registerCount - (if (static) 2 else 3) < 1) throw PatchException("no free register in ${check.name}")
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { $context, $caller }, $LOCAL_CLIPBOARD->isSelf(Landroid/content/Context;Ljava/lang/String;)Z
                        move-result v0
                        if-eqz v0, :check
                        return v0
                    """,
                    ExternalLabel("check", getInstruction(0)),
                )
            }
        }

        // Its clipboard history answers Samsung's package names only, its own included: under a new package name the
        // keyboard was refused its own history.
        val clipboardCallers = mutableListOf<Method>()
        classDefForEach { c ->
            c.methods.filterTo(clipboardCallers) { m ->
                m.returnType == "Z" && m.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
                    !AccessFlags.STATIC.isSet(m.accessFlags) &&
                    m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == CLIPBOARD_CALLER_REFUSED } == true
            }
        }
        if (clipboardCallers.isEmpty()) throw PatchException("clipboard caller check not found")
        for (check in clipboardCallers) {
            mutableClassDefBy(check.definingClass).methods.first {
                it.name == check.name && it.parameterTypes == check.parameterTypes && it.returnType == check.returnType
            }.apply {
                if (implementation!!.registerCount - 2 < 1) throw PatchException("no free register in ${check.name}")
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p1 }, $LOCAL_CLIPBOARD->isSelfPackage(Ljava/lang/String;)Z
                        move-result v0
                        if-eqz v0, :check
                        return v0
                    """,
                    ExternalLabel("check", getInstruction(0)),
                )
            }
        }

        log.info(
            "One UI members: $calls calls, $fields fields; lookups by name: $lookups; Samsung services: $services; " +
                "window flags and types: $windowFlags; provider signature checks: ${checks.size}; clipboard caller checks: " +
                "${clipboardCallers.size}",
        )
        // Measured against 5.9.40.75: 227 calls, 24 field reads, 78 lookups by name, 14 service lookups.
        if (calls < 100 || fields < 10) throw PatchException("only $calls One UI calls and $fields field accesses found")
        if (lookups < 5) throw PatchException("only $lookups resource lookups by name found")
        if (services < 5) throw PatchException("only $services Samsung service lookups found")
        // Measured against 5.9.40.75: 16 flag calls, 12 window types.
        if (windowFlags < 15) throw PatchException("only $windowFlags window flag and type calls found")
    }
}

/**
 * Off One UI the keyboard dies as it starts, on SemSystemProperties (a One UI class). The extension carries stand-ins
 * for the 47 One UI classes it uses, answering "absent"; on a Samsung phone the framework's own classes load first, so
 * they never run there, and OneUi calls One UI's members where the phone has them.
 */
@Suppress("unused")
val enableNonOneUiPatch = bytecodePatch(
    name = "Enable non-One UI ROM support",
    description = "Makes Samsung Keyboard usable on non-One UI ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_KEYBOARD)
    dependsOn(manifestPatch, sprDrawablesPatch, oneUiMembersPatch, systemAccessPatch)
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)
}
