package org.ungoogled.patches.lgquickmemo.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.lg.LG_FRAMEWORK_CODE_IDS
import org.ungoogled.patches.lg.addLgFrameworkLayouts
import org.ungoogled.patches.lg.redirectLgMembers
import org.ungoogled.patches.lg.replaceLgFrameworkIds
import org.ungoogled.patches.lg.replaceLgFrameworkReferences
import org.ungoogled.patches.lgquickmemo.LgQuickMemoConstants.COMPATIBILITY_LG_QUICKMEMO
import org.ungoogled.patches.samsung.redirectCalls
import org.w3c.dom.Element

internal const val LG_QUICKMEMO_EXTENSION = "extensions/lgquickmemo.mpe"

private const val RUNTIME = "Lorg/ungoogled/lgquickmemo"

/** LG's framework id of its SearchView's back button (com.lge:id/searchBackButton). */
private const val LG_SEARCH_BACK = 0x020e00e6

private val log = java.util.logging.Logger.getLogger("LgQuickMemo")

/**
 * QuickMemo+'s layouts and styles name LG's framework resources and its theme (see replaceLgFrameworkReferences); the
 * main screen's collapsing title bar comes from LG's dynamic action bar library, whose stand-in lays out the views
 * that scroll below the bar (lge:layout_behavior, kept as a tag); code inflates LG's dialog layouts.
 */
private val lgResourcesPatch = resourcePatch(
    description = "Gives LG QuickMemo+ the sizes, colours, layouts and day/night theme LG's ROM would.",
) {
    execute {
        val replaced = replaceLgFrameworkReferences(
            attributes = mapOf("0x02010048" to { _ -> """ android:tag="lg_scrolling_view_behavior"""" }),
        )
        log.info("LG framework references: $replaced")
        // Measured against 10.0.30: 71 framework resources and themes.
        if (replaced.size < 60) throw PatchException("only ${replaced.size} LG framework references found")
        addLgFrameworkLayouts("org.ungoogled.lgquickmemo")

        // The navigation drawer's translucent tint sits over LG's blur engine, which only LG's ROM has: without the
        // blur the memo list showed through the categories. Opaque instead.
        var tints = 0
        for ((path, color) in listOf("res/values/colors.xml" to "#fff7f7f7", "res/values-night/colors.xml" to "#ff141414")) {
            document(path).use { xml ->
                val list = xml.getElementsByTagName("color")
                for (i in 0 until list.length) {
                    val element = list.item(i) as Element
                    if (element.getAttribute("name") == "drawer_background_tint") {
                        element.textContent = color
                        tints++
                    }
                }
            }
        }
        if (tints != 2) throw PatchException("drawer_background_tint: found $tints of 2 colours")
    }
}

/**
 * QuickMemo+'s native libraries (LG's pen vector engine, MyScript handwriting recognition, OpenCV, TensorFlow Lite)
 * are 32-bit ARM only, so phones without 32-bit app support refused to install it. The app now also declares 64-bit
 * (an empty placeholder library) and prefers 32-bit where the phone has it: there the native features work as on LG's
 * phones, on 64-bit-only phones the app installs and runs without them.
 */
private val nativeLibrariesPatch = resourcePatch(
    description = "Makes LG QuickMemo+ installable on phones without 32-bit app support.",
) {
    execute {
        val libs = listApkEntries("lib/")
        if (libs.none { it.startsWith("lib/armeabi-v7a/") }) throw PatchException("no 32-bit ARM libraries found: $libs")
        // The x86 copies (two TensorFlow Lite libraries) would make an x86 device pick x86 and lose the ARM ones.
        libs.filter { it.startsWith("lib/x86/") }.forEach { delete(it) }
        for (abi in listOf("arm64-v8a", "x86_64")) {
            get("lib/$abi/libqmemo_64bit_placeholder.so").apply { parentFile.mkdirs(); writeText("placeholder") }
        }
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.setAttribute("android:multiArch", "true")
            application.setAttribute("android:use32bitAbi", "true")
            application.setAttribute("android:extractNativeLibs", "true")
        }
    }
}

/** QuickMemo+'s code names LG framework resources by id: dialog layouts, preference layouts, themes, sizes. */
private val lgFrameworkIdsPatch = bytecodePatch(
    description = "Answers LG framework resource ids in LG QuickMemo+'s code.",
) {
    dependsOn(lgResourcesPatch)
    extendWith(LG_QUICKMEMO_EXTENSION)

    execute {
        // LG's SearchView has a back button (id searchBackButton) that the main screen and the editor look up, show
        // and make close the search; Android's has none (its search icon hides itself again). Compat adds an up arrow.
        var backButtons = 0
        for (type in listOf(
            "Lcom/lge/qmemoplus/ui/main/MainActivity;",
            "Lcom/lge/qmemoplus/ui/editor/toolbar/EditorToolbar;",
        )) {
            for (method in mutableClassDefBy(type).methods) {
                val instructions = method.implementation?.instructions ?: continue
                for (index in instructions.indices) {
                    val literal = instructions[index]
                    if (literal.opcode != Opcode.CONST) continue
                    if ((literal as NarrowLiteralInstruction).narrowLiteral != LG_SEARCH_BACK) continue
                    val register = (literal as OneRegisterInstruction).registerA
                    val call = (index + 1 until minOf(index + 4, instructions.size)).firstOrNull {
                        val candidate = instructions[it]
                        candidate.opcode == Opcode.INVOKE_VIRTUAL &&
                            ((candidate as ReferenceInstruction).reference as MethodReference).name == "findViewById" &&
                            (candidate as FiveRegisterInstruction).registerD == register
                    } ?: throw PatchException("$type->${method.name}: no findViewById after LG's search back button id")
                    val view = (instructions[call] as FiveRegisterInstruction).registerC
                    method.replaceInstruction(
                        call,
                        "invoke-static { v$view, v$register }, " +
                            "$RUNTIME/Compat;->searchBackButton(Landroid/view/View;I)Landroid/view/View;",
                    )
                    backButtons++
                }
            }
        }
        if (backButtons != 2) throw PatchException("found $backButtons of LG QuickMemo+'s 2 search back buttons")

        val replaced = replaceLgFrameworkIds(
            "$RUNTIME/LgRes;",
            LG_FRAMEWORK_CODE_IDS + mapOf(
                // LG's theme for a theme colour: the app's own theme (on LgDayNight).
                0x020a01ac to "style/QMemoplusTheme",
                // LG's memory tier: the app catches the missing string ("not defined").
                0x020d002f to "",
                // LG's SearchView back button: Compat.searchBackButton (above) answers it, the literal stays.
                LG_SEARCH_BACK to "",
            ),
        )
        log.info("LG framework ids in code: ${replaced.map { "0x%08x=%d".format(it.key, it.value) }}")
        if (replaced.size < 15) throw PatchException("only ${replaced.size} LG framework ids found in code")
        // Code also looks LG's framework resources up by name (getIdentifier(name, type, "com.lge")): LgRes answers.
        val lookups = redirectCalls(
            "$RUNTIME/LgRes;",
            mapOf("Landroid/content/res/Resources;" to setOf("getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I")),
        )
        log.info("Resource lookups by name: $lookups")
        // Measured against 10.0.30: 75.
        if (lookups < 60) throw PatchException("only $lookups resource lookups found")
    }
}

/**
 * Android's hidden members QuickMemo+ uses that Android refuses an installed app (it targets Android 11): the
 * window's windowing mode, the display's base density and a table-empty query. They go through Lg like LG's own
 * additions, which answers them with public API or "not there".
 */
private val HIDDEN_MEMBERS = setOf(
    "Landroid/content/res/Configuration;->windowConfiguration",
    "Landroid/app/WindowConfiguration;->getWindowingMode",
    "Landroid/view/IWindowManager;->getBaseDisplayDensity",
    "Landroid/database/DatabaseUtils;->queryIsEmpty",
)

/** Sends QuickMemo+'s uses of LG's additions to Android classes through Lg (see redirectLgMembers). */
private val lgMembersPatch = bytecodePatch(
    description = "Answers LG's additions to Android classes where the phone has none.",
) {
    extendWith(LG_QUICKMEMO_EXTENSION)

    execute {
        val (calls, fields) = redirectLgMembers("$RUNTIME/Lg;", "$RUNTIME/LgShims;", HIDDEN_MEMBERS)
        log.info("LG and hidden members: $calls calls, $fields fields")
        // Measured against 10.0.30: 19 calls and 27 field accesses.
        if (calls + fields < 40) throw PatchException("only $calls LG calls and $fields field accesses found")

        // The notch height came from the hidden DisplayInfo, phone number links from the platform's hidden copy of
        // libphonenumber: both now from public API (Compat).
        fun replaceBody(type: String, name: String, parameters: List<String>, body: String) {
            val method = mutableClassDefBy(type).methods.firstOrNull { it.name == name && it.parameterTypes == parameters }
                ?: throw PatchException("$type->$name not found")
            method.addInstructions(0, body)
        }
        replaceBody(
            "Lcom/lge/qmemoplus/utils/device/DeviceInfoUtils;", "getNotchHeight", listOf("Landroid/view/Display;"),
            """
                invoke-static { p0 }, $RUNTIME/Compat;->notchHeight(Landroid/view/Display;)I
                move-result v0
                return v0
            """,
        )
        // Locked memos use LG's Privacy Lock app (com.lge.privacylock), absent off LG's ROM: Lock did nothing. The
        // phone's own screen lock (PIN, pattern, fingerprint) stands in for it (Compat).
        replaceBody(
            "Lcom/lge/privacylock/util/PrivacyLock;", "existLock", listOf("Landroid/content/Context;"),
            """
                invoke-static { p0 }, $RUNTIME/Compat;->existLock(Landroid/content/Context;)Z
                move-result v0
                return v0
            """,
        )
        replaceBody(
            "Lcom/lge/privacylock/util/PrivacyLock;", "startActivityBy",
            listOf("Ljava/lang/Object;", "I", "I", "I", "Z", "I", "Z"),
            """
                invoke-static { p0, p1, p2 }, $RUNTIME/Compat;->privacyLock(Ljava/lang/Object;II)Z
                move-result v0
                return v0
            """,
        )
        replaceBody(
            "Lcom/lge/qmemoplus/utils/AdvancedLinkify;", "gatherTelLinks",
            listOf("Ljava/util/ArrayList;", "Landroid/text/Spannable;", "Landroid/content/Context;"),
            """
                invoke-static { p0, p1 }, $RUNTIME/Compat;->gatherTelLinks(Ljava/util/ArrayList;Landroid/text/Spannable;)V
                return-void
            """,
        )
    }
}

/**
 * On LG's phones QuickMemo+ is a system app and keeps some state in Android's system settings; a re-signed copy may not
 * write them, and the first write (in Application.onCreate) killed it. Writes it may not make go to its own storage.
 */
private val systemSettingsPatch = bytecodePatch(
    description = "Keeps LG QuickMemo+'s own system settings in its storage.",
) {
    extendWith(LG_QUICKMEMO_EXTENSION)

    execute {
        val moved = redirectCalls(
            "$RUNTIME/LgSettings;",
            mapOf(
                "Landroid/provider/Settings\$System;" to setOf(
                    "putInt(Landroid/content/ContentResolver;Ljava/lang/String;I)Z",
                    "getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I",
                ),
            ),
        )
        log.info("System settings calls: $moved")
        // Measured against 10.0.30: 6 writes, 5 reads.
        if (moved < 11) throw PatchException("only $moved system settings calls found")
    }
}

/**
 * QuickMemo+ asks the application context for the display at start-up (and in 28 other places); Android 11+ refuses
 * that for a context without a window once an app targets Android 11, and the app died before showing anything.
 */
private val displayPatch = bytecodePatch(
    description = "Answers LG QuickMemo+'s display and status bar calls that Android refuses an installed app.",
) {
    extendWith(LG_QUICKMEMO_EXTENSION)

    execute {
        val moved = redirectCalls(
            "$RUNTIME/Compat;",
            mapOf("Landroid/content/Context;" to setOf("getDisplay()Landroid/view/Display;")),
        )
        log.info("Display lookups: $moved")
        // Measured against 10.0.30: 29.
        if (moved < 20) throw PatchException("only $moved display lookups found")
        // The "navigation bar lock" while drawing disables the status bar's buttons: a system app's privilege, and the
        // SecurityException killed the editor. Off LG's ROM nothing is locked.
        val statusBar = redirectCalls(
            "$RUNTIME/Compat;",
            mapOf("Landroid/app/StatusBarManager;" to setOf("disable(I)V", "collapsePanels()V")),
        )
        log.info("Status bar calls: $statusBar")
        // Measured against 10.0.30: 6 + 1.
        if (statusBar < 7) throw PatchException("only $statusBar status bar calls found")
    }
}

/**
 * QuickMemo+ reports feature use (172 calls: saves, reminders, pen tools, ...) to LG's on-device usage log service
 * (com.lge.mlt, MLT), which collects it for LG; off LG's ROM that service is absent, and the call itself (as another
 * user, a system app's privilege) killed the save. The reports now go nowhere. Quick mode starts its screen as the
 * current user (also a system app's privilege): it starts it as the app itself.
 */
private val usageLogPatch = bytecodePatch(
    description = "Turns off LG QuickMemo+'s usage reports to LG's log service.",
) {
    extendWith(LG_QUICKMEMO_EXTENSION)

    execute {
        val utils = mutableClassDefBy("Lcom/lge/qmemoplus/utils/CommonUtils;")
        val logs = utils.methods.filter { it.name == "addMLTLog" && it.returnType == "V" }
        if (logs.size != 4) throw PatchException("expected 4 addMLTLog methods, found ${logs.size}")
        logs.forEach { it.addInstructions(0, "return-void") }
        val moved = redirectCalls(
            mapOf(
                "Lcom/lge/qmemoplus/quickmode/QuickModeLaunchingService;->startActivityAsUser(Landroid/content/Intent;Landroid/os/Bundle;Landroid/os/UserHandle;)V" to
                    "$RUNTIME/Compat;->startActivityAsUser(Landroid/content/Context;Landroid/content/Intent;Landroid/os/Bundle;Landroid/os/UserHandle;)V",
            ),
        )
        if (moved != 1) throw PatchException("expected 1 startActivityAsUser call, found $moved")
    }
}

/**
 * QuickMemo+'s text and pen colour pickers are LG's SUI colour picker (a system library, com.lge.sui.widget); off LG's
 * ROM the app found no SUI, offered no pen colours and crashed on the text colour button. The extension's SUI stand-in
 * is a working palette, so the app is told SUI is there. Reminder dates and times use SUI's pickers too (Android's
 * date and time pickers in the stand-in).
 */
private val colorPickerPatch = bytecodePatch(
    description = "Gives LG QuickMemo+ its colour picker.",
) {
    extendWith(LG_QUICKMEMO_EXTENSION)

    execute {
        val method = mutableClassDefBy("Lcom/lge/qmemoplus/common/colorpicker/ColorPickerWrapper;").methods
            .firstOrNull { it.name == "isSUIAvailable" && it.returnType == "Z" }
            ?: throw PatchException("ColorPickerWrapper.isSUIAvailable not found")
        method.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
    }
}

/**
 * Off LG's ROM QuickMemo+ could not be installed (32-bit-only native code on 64-bit-only phones), and once installed it
 * died on LG framework classes, resources and system privileges. The extension carries stand-ins for the LG classes it
 * uses (generated by .github/scripts/lg-standins); on an LG phone the framework's own classes load first.
 */
@Suppress("unused")
val enableNonLgPatch = bytecodePatch(
    name = "Enable non-LG ROM support",
    description = "Makes LG QuickMemo+ installable and usable on non-LG ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LG_QUICKMEMO)
    dependsOn(
        lgResourcesPatch, nativeLibrariesPatch, lgFrameworkIdsPatch, lgMembersPatch, systemSettingsPatch, displayPatch,
        usageLogPatch, colorPickerPatch,
    )
    extendWith(LG_QUICKMEMO_EXTENSION)
}
