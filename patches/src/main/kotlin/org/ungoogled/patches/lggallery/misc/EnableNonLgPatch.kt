package org.ungoogled.patches.lggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.lg.LG_FRAMEWORK_CODE_IDS
import org.ungoogled.patches.lg.addLgFrameworkLayouts
import org.ungoogled.patches.lg.redirectLgMembers
import org.ungoogled.patches.lg.replaceLgFrameworkIds
import org.ungoogled.patches.lg.replaceLgFrameworkReferences
import org.ungoogled.patches.lggallery.LgGalleryConstants.COMPATIBILITY_LG_GALLERY
import org.ungoogled.patches.samsung.redirectCalls
import org.w3c.dom.Element

internal const val LG_GALLERY_EXTENSION = "extensions/lggallery.mpe"

private const val RUNTIME = "Lorg/ungoogled/lggallery"

private val log = java.util.logging.Logger.getLogger("LgGallery")

/**
 * LG framework references only LG Gallery names (the shared answers are in lg/LgResources.kt): its translucent and
 * full-screen theme variants, list separators, coach marks (first-use tips) and "+" icons.
 */
private val GALLERY_ANSWERS = mapOf(
    "@0x020a01cf" to "@style/LgDayNight.NoActionBar.Fullscreen", // style/Theme.LGE.White.NoActionBar.Fullscreen
    "@0x020a01d5" to "@style/LgGallery.Translucent", // style/Theme.LGE.White.Translucent.NoTitleBar
    "@0x020a0263" to "@android:style/Widget.Material.TextView", // style/Widget.Material.TextView.ListSeparator
    "@0x020a02c5" to "@android:style/Widget.Material.Button", // style/Widget.Material.Button.CoachMark
    "@0x020a02f9" to "@android:style/TextAppearance.Material.Body1", // style/LGTextAppearance.CoachMark.Message
    "@0x0202049f" to "@android:drawable/ic_menu_add", // drawable/ic_menu_sub_add
    "@0x02020501" to "@android:drawable/ic_menu_add", // drawable/ic_t_add
    "@0x02080025" to "?android:attr/colorAccent", // color/btn_blocked_check_on_outer
    "?0x0201002e" to "@dimen/lg_action_bar_tab_size", // attr/actionBarTabSize: 75dp, 50dp in landscape
)

/** LG's framework widgets in LG Gallery's layouts, by tag, and Android's widgets they extend. */
private val LG_WIDGETS = mapOf("TabHostEx" to "TabHost", "TabWidgetEx" to "TabWidget", "SearchViewEx" to "SearchView")

/**
 * LG Gallery's layouts and styles name LG's framework resources and themes (see replaceLgFrameworkReferences); its
 * code inflates LG's dialog title with a button (dialog_t_9) and asks for two of LG's text sizes.
 */
private val lgResourcesPatch = resourcePatch(
    description = "Gives LG Gallery the sizes, colours, layouts and day/night theme LG's ROM would.",
) {
    execute {
        // The settings screens lay their content out below LG's app bar (lge:layout_behavior): a tag the
        // CoordinatorLayout stand-in reads.
        val replaced = replaceLgFrameworkReferences(
            extra = GALLERY_ANSWERS,
            attributes = mapOf("0x02010048" to { _ -> """ android:tag="lg_scrolling_view_behavior"""" }),
        )
        log.info("LG framework references: $replaced")
        // Measured against 9.60.32: 55 framework resources and themes.
        if (replaced.size < 45) throw PatchException("only ${replaced.size} LG framework references found")
        addLgFrameworkLayouts("org.ungoogled.lggallery")

        // LG's framework widgets the layouts name (android.widget.TabHostEx, TabWidgetEx, SearchViewEx: LG's
        // subclasses of Android's, which only LG's ROM has): Android's own.
        var widgets = 0
        for (path in listDecodableFiles("res/**").filter { it.startsWith("res/layout") && it.endsWith(".xml") }) {
            val file = get(path)
            if (!file.isFile) continue
            val text = file.readText()
            val changed = LG_WIDGETS.entries.fold(text) { xml, (lg, android) ->
                xml.replace(Regex("""(</?)$lg(?=[\s>/])""")) { it.groupValues[1] + android }
            }
            if (changed != text) {
                file.writeText(changed)
                widgets++
            }
        }
        // Measured against 9.60.32: the tab bar and the search bar layouts.
        if (widgets < 2) throw PatchException("LG framework widgets found in only $widgets layouts")

        // The selection screens' button bars take their size from LG's button bar style (match_parent x
        // wrap_content); Android's sets none, and inflating them failed ("You must supply a layout_width attribute").
        var buttonBars = 0
        for (path in listDecodableFiles("res/**").filter { it.startsWith("res/layout") && it.endsWith(".xml") }) {
            val file = get(path)
            if (!file.isFile || !file.readText().contains("buttonBarStyle")) continue
            document(path).use { xml ->
                val elements = xml.getElementsByTagName("*")
                for (i in 0 until elements.length) {
                    val element = elements.item(i) as Element
                    if (!element.getAttribute("style").contains("buttonBarStyle")) continue
                    if (element.getAttribute("android:layout_width").isEmpty()) {
                        element.setAttribute("android:layout_width", "match_parent")
                        buttonBars++
                    }
                    if (element.getAttribute("android:layout_height").isEmpty()) element.setAttribute("android:layout_height", "wrap_content")
                }
            }
        }
        // Measured against 9.60.32: 4 (two layouts, each also in a 12-key keypad variant).
        if (buttonBars < 2) throw PatchException("only $buttonBars LG button bars without a size found")

        document("res/values/styles.xml").use { xml ->
            xml.documentElement.appendChild(xml.createElement("style").apply {
                setAttribute("name", "LgGallery.Translucent")
                setAttribute("parent", "@style/LgDayNight.NoActionBar")
                for ((key, value) in listOf(
                    "android:windowIsTranslucent" to "true",
                    "android:windowBackground" to "@android:color/transparent",
                    "android:windowContentOverlay" to "@null",
                    "android:windowIsFloating" to "false",
                )) {
                    appendChild(xml.createElement("item").apply { setAttribute("name", key); textContent = value })
                }
            })
        }
        for ((path, values) in listOf(
            "res/values/dimens.xml" to listOf(
                "lg_type_b02_sp" to "12.0sp", "lg_type_d03_sp" to "16.0sp", "lg_action_bar_tab_size" to "75.0dp",
                // Looked up by name (LgRes): the end margin of LG's action menu, beside the search bar.
                "lg_action_menu_view_margin_end" to "0.0dp",
            ),
            "res/values-land/dimens.xml" to listOf("lg_action_bar_tab_size" to "50.0dp"),
        )) {
            val file = get(path)
            if (!file.isFile) {
                file.parentFile.mkdirs()
                file.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
            }
            document(path).use { xml ->
                for ((name, value) in values) {
                    xml.documentElement.appendChild(xml.createElement("dimen").apply { setAttribute("name", name); textContent = value })
                }
            }
        }
        // LG's dialog title with a button at its end (the name dialog's "pick a suggestion" button).
        get("res/layout/lg_dialog_t_9.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
            |<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:orientation="horizontal"
            |    android:gravity="center_vertical" android:layout_width="match_parent" android:layout_height="wrap_content"
            |    android:paddingStart="24dp" android:paddingEnd="16dp" android:paddingTop="20dp" android:paddingBottom="4dp">
            |    <TextView android:id="@android:id/title" android:layout_width="0dp" android:layout_height="wrap_content"
            |        android:layout_weight="1" android:textAppearance="?android:attr/textAppearanceLarge" />
            |    <ImageButton android:id="@android:id/button1" android:layout_width="40dp" android:layout_height="40dp"
            |        android:layout_marginStart="8dp" android:scaleType="center"
            |        android:background="?android:attr/selectableItemBackgroundBorderless" />
            |</LinearLayout>
            |""".trimMargin(),
        )
    }
}

/**
 * LG Gallery's native libraries (Quram's JPEG decoder, the depth, tilt and 3D photo effects, ...) are 32-bit ARM only,
 * so phones without 32-bit app support refused to install it. It now also declares 64-bit (an empty placeholder
 * library) and runs as a 64-bit app on 64-bit phones, without those libraries (see the native calls patch); 32-bit-only
 * phones still get them. Not multiArch (QuickMemo+'s way to prefer 32-bit): Android 15 then refuses the install on a
 * phone that also runs 32-bit apps once Morphe Manager has stripped the 32-bit libraries.
 */
private val nativeLibrariesPatch = resourcePatch(
    description = "Makes LG Gallery installable on phones without 32-bit app support.",
) {
    execute {
        val libs = listApkEntries("lib/")
        if (libs.none { it.startsWith("lib/armeabi-v7a/") }) throw PatchException("no 32-bit ARM libraries found: $libs")
        libs.filter { it.startsWith("lib/x86/") }.forEach { delete(it) }
        for (abi in listOf("arm64-v8a", "x86_64")) {
            get("lib/$abi/libgallery_64bit_placeholder.so").apply { parentFile.mkdirs(); writeText("placeholder") }
        }
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            // Morphe compresses the files it adds; stored libraries would need extractNativeLibs="false".
            application.setAttribute("android:extractNativeLibs", "true")
        }
    }
}

/** The classes whose native methods are in LG Gallery's 32-bit-only libraries (the database library aside). */
private val NATIVE_CLASSES = setOf(
    "Lcom/arcsoft/depthextractor/jni/ArcDepthExtractor;",
    "Lcom/atom/utils/AtomUtils;",
    "Lcom/lge/tilt3d/Image3D;",
    "Lcom/lge/tilt3d/TiltImageRenderer;",
    "Lcom/nexstreaming/scmbsdk/nxSCMBSDK;",
    "Lcom/omnivirt/sdk/photo3d/Photo3DEngine;",
    "Lcom/quramsoft/graphics/QrBitmapFactory;",
    "Lcom/quramsoft/graphics/QrBitmapRegionDecoder;",
    "Lcom/quramsoft/qrb/QrBitmapDecoder;",
    "Lcom/quramsoft/qrb/QuramBitmapFactory;",
    "Lcom/quramsoft/qrb/QuramBitmapRegionDecoderNative;",
    "Lcom/quramsoft/qrb/QuramImageUtils;",
    "Lcom/quramsoft/xiv/XIVCoder;",
)

/**
 * On a 64-bit-only phone the 32-bit libraries cannot load: the first decode died on UnsatisfiedLinkError (their
 * classes catch only Exception around loadLibrary). Every call into them now answers "nothing" there, and the app
 * falls back to Android's decoders as it does for a failed decode; where the libraries load, they run as before.
 */
private val nativeCallsPatch = bytecodePatch(
    description = "Lets LG Gallery run without its 32-bit native libraries.",
) {
    extendWith(LG_GALLERY_EXTENSION)

    execute {
        val wrapped = guardNativeMethods(NATIVE_CLASSES, "$RUNTIME/Natives;->ok()Z")
        log.info("Native methods guarded: $wrapped")
        // Measured against 9.60.32: 13 classes.
        if (wrapped.size != NATIVE_CLASSES.size) throw PatchException("guarded ${wrapped.size} of ${NATIVE_CLASSES.size} native classes")
        val loads = redirectCalls(
            mapOf("Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V" to "$RUNTIME/Natives;->loadLibrary(Ljava/lang/String;)V"),
        )
        log.info("loadLibrary calls: $loads")
        if (loads < 15) throw PatchException("only $loads loadLibrary calls found")

        // JPEGs zoom through Quram's region decoder only: where it cannot load, Android's (the one Gallery uses for
        // every other format) instead, or a photo could not be zoomed in.
        val region = mutableClassDefBy("Lcom/lge/gallery/decoder/GalleryRegionDecoderFactory;").methods.firstOrNull {
            it.name == "setUseNativeRegion" && it.parameterTypes.map { p -> p.toString() } == listOf("Z") && it.returnType == "V"
        } ?: throw PatchException("GalleryRegionDecoderFactory.setUseNativeRegion not found")
        region.addInstructions(
            0,
            """
                invoke-static { p1 }, $RUNTIME/Natives;->androidRegionDecoder(Z)Z
                move-result p1
            """,
        )
    }
}

/** SQLCipher's classes that LG Gallery's own code uses, and Android's SQLite classes with the same members. */
private val SQLCIPHER_TYPES = mapOf(
    "Lnet/sqlcipher/database/SQLiteDatabase;" to "Landroid/database/sqlite/SQLiteDatabase;",
    "Lnet/sqlcipher/database/SQLiteDatabase\$CursorFactory;" to "Landroid/database/sqlite/SQLiteDatabase\$CursorFactory;",
    "Lnet/sqlcipher/database/SQLiteOpenHelper;" to "Landroid/database/sqlite/SQLiteOpenHelper;",
    "Lnet/sqlcipher/database/SQLiteException;" to "Landroid/database/sqlite/SQLiteException;",
    "Lnet/sqlcipher/database/SQLiteFullException;" to "Landroid/database/sqlite/SQLiteFullException;",
    "Lnet/sqlcipher/database/SQLiteConstraintException;" to "Landroid/database/sqlite/SQLiteConstraintException;",
    "Lnet/sqlcipher/Cursor;" to "Landroid/database/Cursor;",
)

private const val SECURE_HELPER = "Lcom/lge/gallery/cloud/SQLiteSecureOpenHelper;"

/**
 * LG Gallery keeps its media index (its copy of MediaStore with LG's columns: bursts, tags, memos, ...) in an
 * encrypted SQLCipher database, whose library is native and 32-bit only: on a 64-bit-only phone the index could not be
 * opened and no album showed. It now uses Android's own SQLite (same SQL, same tables, not encrypted: the app's
 * private storage only) on every phone.
 */
private val databasePatch = bytecodePatch(
    description = "Keeps LG Gallery's media index in Android's SQLite.",
) {
    execute {
        // The helper opens the database with its key (getReadableDatabase(key) / getWritableDatabase(key) of
        // SQLCipher's helper): Android's helper takes none. SQLCipher's native library load goes too.
        var opens = 0
        var loads = 0
        val helper = mutableClassDefBy(SECURE_HELPER)
        for (method in helper.methods) {
            val instructions = method.implementation?.instructions ?: continue
            for (index in instructions.indices) {
                val insn = instructions[index]
                val reference = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (insn.opcode == Opcode.INVOKE_STATIC && reference.name == "loadLibs" &&
                    reference.definingClass == "Lnet/sqlcipher/database/SQLiteDatabase;"
                ) {
                    method.replaceInstruction(index, "nop")
                    loads++
                } else if (insn.opcode == Opcode.INVOKE_VIRTUAL && reference.definingClass == SECURE_HELPER &&
                    reference.name in setOf("getReadableDatabase", "getWritableDatabase") &&
                    reference.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;")
                ) {
                    val receiver = (insn as FiveRegisterInstruction).registerC
                    method.replaceInstruction(
                        index,
                        "invoke-super { v$receiver }, Lnet/sqlcipher/database/SQLiteOpenHelper;->${reference.name}()Lnet/sqlcipher/database/SQLiteDatabase;",
                    )
                    opens++
                }
            }
        }
        if (opens != 2 || loads != 1) throw PatchException("SQLiteSecureOpenHelper: found $opens of 2 keyed opens, $loads of 1 library loads")

        val retyped = retypeClasses(SQLCIPHER_TYPES) { it.startsWith("Lnet/sqlcipher/") }
        log.info("Classes moved to Android's SQLite: $retyped")
        // Measured against 9.60.32: the helper and its wrapper, the media provider (3 classes), its debug utilities (2).
        if (retyped.size != 7) throw PatchException("expected 7 classes using SQLCipher, found ${retyped.size}: $retyped")
        if (mutableClassDefBy(SECURE_HELPER).superclass != "Landroid/database/sqlite/SQLiteOpenHelper;") {
            throw PatchException("SQLiteSecureOpenHelper does not extend SQLCipher's helper")
        }
    }
}

/** LG Gallery's code names LG framework resources by id: two theme sizes, two layouts, a theme, two text sizes. */
private val lgFrameworkIdsPatch = bytecodePatch(
    description = "Answers LG framework resource ids in LG Gallery's code.",
) {
    dependsOn(lgResourcesPatch)
    extendWith(LG_GALLERY_EXTENSION)

    execute {
        val replaced = replaceLgFrameworkIds(
            "$RUNTIME/LgRes;",
            LG_FRAMEWORK_CODE_IDS + mapOf(
                // LG's tab bar and expanded action bar heights (theme attributes): Android's action bar height.
                0x0201002e to "0x010102eb", // attr/actionBarTabSize
                0x02010032 to "0x010102eb", // attr/expandActionBarSize
                0x02030011 to "new:layout/lg_dialog_t_9", // layout/dialog_t_9: a dialog title with a button
                0x020300b1 to "0x01090009", // layout/simple_spinner_dropdown_item: Android's
                // The external-display viewer's theme: Android's translucent full-screen theme.
                0x020a01d6 to "0x01030011", // style/Theme.LGE.White.Translucent.NoTitleBar.Fullscreen
                0x020a0401 to "", // not a resource: a flag value in the cloud item code
                0x020c0123 to "new:dimen/lg_type_b02_sp", // dimen/type_b02_sp
                0x020c012d to "new:dimen/lg_type_d03_sp", // dimen/type_d03_sp
            ),
        )
        log.info("LG framework ids in code: ${replaced.map { "0x%08x=%d".format(it.key, it.value) }}")
        // Measured against 9.60.32: 7 ids (9 sites), the flag value aside.
        if (replaced.size < 7) throw PatchException("only ${replaced.size} LG framework ids found in code")
        val lookups = redirectCalls(
            "$RUNTIME/LgRes;",
            mapOf("Landroid/content/res/Resources;" to setOf("getIdentifier(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I")),
        )
        log.info("Resource lookups by name: $lookups")
        if (lookups < 15) throw PatchException("only $lookups resource lookups found")
    }
}

/**
 * Android's hidden members LG Gallery uses that Android refuses an installed app (it targets Android 12): the window's
 * windowing mode, background blur, the task list of LG's dual screen, rotation watching. They go through Lg like LG's
 * own additions, which answers them where the phone allows, else "not there".
 */
private val HIDDEN_MEMBERS = setOf(
    "Landroid/app/ActivityTaskManager\$RootTaskInfo;->displayId",
    "Landroid/app/ActivityTaskManager\$RootTaskInfo;->visible",
    "Landroid/app/IActivityManager;->getAllRootTaskInfos",
    "Landroid/app/WindowConfiguration;->getWindowingMode",
    "Landroid/content/res/Configuration;->windowConfiguration",
    "Landroid/view/ViewRootImpl;->createBackgroundBlurDrawable",
    "Landroid/view/IWindowManager;->watchRotation",
    "Landroid/view/IWindowManager;->setBlockSwipeNavigationbarFromApps",
)

private fun Instruction.isActivityManagerExCast() =
    opcode == Opcode.CHECK_CAST && ((this as ReferenceInstruction).reference as? TypeReference)?.type == "Landroid/app/ActivityManagerEx;"

/** Sends LG Gallery's uses of LG's additions to Android classes and of hidden members through Lg. */
private val lgMembersPatch = bytecodePatch(
    description = "Answers LG's additions to Android classes where the phone has none.",
) {
    extendWith(LG_GALLERY_EXTENSION)

    execute {
        val (calls, fields) = redirectLgMembers("$RUNTIME/Lg;", "$RUNTIME/LgShims;", HIDDEN_MEMBERS)
        log.info("LG and hidden members: $calls calls, $fields fields")
        if (calls + fields < 8) throw PatchException("only $calls LG calls and $fields field accesses found")

        // The XMP reader's offsets list is Android's hidden LongArray: the extension's copy instead.
        val retyped = retypeClasses(mapOf("Landroid/util/LongArray;" to "$RUNTIME/LongArray;")) { false }
        log.info("Classes moved off android.util.LongArray: $retyped")
        if (retyped.size != 2) throw PatchException("expected 2 classes using android.util.LongArray, found $retyped")

        // On LG's ROM the "activity" system service is LG's ActivityManagerEx (dual screen task moves): Gallery casts
        // Android's ActivityManager to it (ClassCastException leaving a photo). Lg.activityManagerEx answers the
        // stand-in, whose answers say "no second screen".
        var casts = 0
        val castSites = mutableListOf<Pair<String, Method>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.implementation?.instructions?.any { it.isActivityManagerExCast() } == true) castSites += c.type to m
            }
        }
        for ((type, method) in castSites) {
            val mutable = mutableClassDefBy(type).methods.first {
                it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
            }
            val instructions = mutable.implementation!!.instructions
            for (index in instructions.indices.reversed()) {
                if (!instructions[index].isActivityManagerExCast()) continue
                val register = (instructions[index] as OneRegisterInstruction).registerA
                mutable.replaceInstruction(
                    index,
                    "invoke-static { v$register }, $RUNTIME/Compat;->activityManagerEx(Ljava/lang/Object;)Landroid/app/ActivityManagerEx;",
                )
                mutable.addInstruction(index + 1, "move-result-object v$register")
                casts++
            }
        }
        // Measured against 9.60.32: 3 casts (GalleryUtils).
        if (casts != 3) throw PatchException("expected 3 casts to LG's ActivityManagerEx, found $casts")

        // Selection screens hide the action mode's close button, which on LG's ROM is a LinearLayout around it:
        // Android's is the button itself (ClassCastException entering selection, e.g. "Add files" to a new album).
        val hide = mutableClassDefBy("Lcom/lge/gallery/ui/ActionModeHandler;").methods.firstOrNull {
            it.name == "hideDoneButtonLayout" && it.parameterTypes.isEmpty() && it.returnType == "V"
        } ?: throw PatchException("ActionModeHandler.hideDoneButtonLayout not found")
        val cast = hide.implementation!!.instructions.indexOfFirst {
            it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == "Landroid/widget/LinearLayout;"
        }
        if (cast < 0) throw PatchException("ActionModeHandler.hideDoneButtonLayout: no LinearLayout cast")
        val castRegister = (hide.implementation!!.instructions[cast] as OneRegisterInstruction).registerA
        hide.replaceInstruction(
            cast,
            "invoke-static { v$castRegister }, $RUNTIME/Compat;->actionModeCloseLayout(Landroid/view/View;)Landroid/widget/LinearLayout;",
        )
        hide.addInstruction(cast + 1, "move-result-object v$castRegister")

        // Background blur needs the hidden BackgroundBlurDrawable: the blurred panels get their plain tint instead.
        val blur = mutableClassDefBy("Lcom/lge/gallery/ui/common/GalleryBlurView;").methods.firstOrNull {
            it.name == "setBackgroundBlurDrawable" && it.parameterTypes.map { p -> p.toString() } == listOf("I") && it.returnType == "Z"
        } ?: throw PatchException("GalleryBlurView.setBackgroundBlurDrawable not found")
        blur.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
    }
}

/**
 * Gallery lists the phone's storage (internal, SD card, USB drive) through Android's hidden storage classes, which
 * Android refuses an installed app: it found no storage at all (no Trash, no SD card albums). The list now comes from
 * the public StorageVolume API (Compat.storageInfo), and the internal storage's id is Android's constant.
 */
private val storagePatch = bytecodePatch(
    description = "Lists the phone's storage for LG Gallery.",
) {
    extendWith(LG_GALLERY_EXTENSION)

    execute {
        val create = mutableClassDefBy("Lcom/lge/gallery/sys/StorageHelper\$NosManager;").methods.firstOrNull {
            it.name == "createStorageInfo" && it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Context;") &&
                it.returnType == "Ljava/util/List;"
        } ?: throw PatchException("StorageHelper.NosManager.createStorageInfo not found")
        create.addInstructions(
            0,
            """
                invoke-static { p1 }, $RUNTIME/Compat;->storageInfo(Landroid/content/Context;)Ljava/util/List;
                move-result-object v0
                return-object v0
            """,
        )
        val primary = mutableClassDefBy("Lcom/lge/gallery/sys/StorageHelper\$Manager;").methods.firstOrNull {
            it.name == "getPrimaryStorageId" && it.parameterTypes.isEmpty() && it.returnType == "I"
        } ?: throw PatchException("StorageHelper.Manager.getPrimaryStorageId not found")
        primary.addInstructions(0, "const v0, 0x10001\nreturn v0")
    }
}

/**
 * Gallery keeps its bottom tabs and bars clear of the navigation bar with sizes from LG's ROM (its resources and
 * settings) and a hidden field of Android's internal R class (gesture navigation, refused: buttons were always
 * assumed); off LG's ROM the tabs sat under the navigation bar. The sizes now come from the window's insets.
 */
private val navigationBarPatch = bytecodePatch(
    description = "Tells LG Gallery how the phone navigates.",
) {
    extendWith(LG_GALLERY_EXTENSION)

    execute {
        val helper = mutableClassDefBy("Lcom/lge/gallery/sys/NavigationBarHelper;")
        val has = helper.methods.firstOrNull { it.name == "hasNavigationBar" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
            ?: throw PatchException("NavigationBarHelper.hasNavigationBar not found")
        has.addInstructions(
            0,
            """
                invoke-static {}, $RUNTIME/Compat;->hasNavigationBar()Z
                move-result v0
                return v0
            """,
        )
        val viewUtils = mutableClassDefBy("Lcom/lge/gallery/ui/ViewUtils;")
        for ((name, answer) in listOf("getRealNavigationHeight" to "navigationHeight", "getRealNavigationWidth" to "navigationWidth")) {
            val method = viewUtils.methods.firstOrNull {
                it.name == name && it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/app/Activity;", "Z") && it.returnType == "I"
            } ?: throw PatchException("ViewUtils.$name not found")
            method.addInstructions(
                0,
                """
                    invoke-static { p0, p1 }, $RUNTIME/Compat;->$answer(Landroid/app/Activity;Z)I
                    move-result v0
                    if-ltz v0, :gallery
                    return v0
                    :gallery
                    nop
                """,
            )
        }
        val gesture = helper.methods.firstOrNull {
            it.name == "isGestureNavigationBar" && it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Context;") &&
                it.returnType == "Z"
        } ?: throw PatchException("NavigationBarHelper.isGestureNavigationBar not found")
        gesture.addInstructions(
            0,
            """
                invoke-static { p0 }, $RUNTIME/Compat;->isGestureNavigation(Landroid/content/Context;)Z
                move-result v0
                return v0
            """,
        )
    }
}

/**
 * Gallery reports feature use (menus, grid sizes, storage, ...) to LG's on-device usage log service (com.lge.mlt, MLT),
 * which collects it for LG. Off LG's ROM that service is absent; the reports now go nowhere at all.
 */
private val usageLogPatch = bytecodePatch(
    description = "Turns off LG Gallery's usage reports to LG's log service.",
) {
    execute {
        val send = mutableClassDefBy("Lcom/lge/gallery/sys/MltHelper\$LoggerImpl;").methods.firstOrNull {
            it.name == "startService" && it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/String;", "Ljava/lang/String;", "I") &&
                it.returnType == "V"
        } ?: throw PatchException("MltHelper.LoggerImpl.startService not found")
        send.addInstructions(0, "return-void")
    }
}

/**
 * LG's permission helper closes a screen that lacks permissions and asks for them on its own screen, which opens the
 * Gallery screen again once granted (Gallery relies on that: it has no permission result handler). The extension's
 * stand-in does the same through its own invisible screen, declared here.
 */
private val permissionScreenPatch = resourcePatch(
    description = "Adds LG Gallery's permission request screen.",
) {
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(manifest.createElement("activity").apply {
                setAttribute("android:name", "org.ungoogled.lggallery.PermissionRequestActivity")
                setAttribute("android:exported", "false")
                setAttribute("android:excludeFromRecents", "true")
                setAttribute("android:noHistory", "true")
                setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                setAttribute("android:configChanges", "orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden")
            })
        }
    }
}

/**
 * Off LG's ROM LG Gallery could not be installed (32-bit-only native code on 64-bit-only phones), and once installed it
 * died on LG framework classes and resources, its native database and decoders. The extension carries stand-ins for
 * the LG classes it uses (generated by .github/scripts/lg-standins); on an LG phone the framework's own classes load
 * first.
 */
@Suppress("unused")
val enableNonLgPatch = bytecodePatch(
    name = "Enable non-LG ROM support",
    description = "Makes LG Gallery installable and usable on non-LG ROMs.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_LG_GALLERY)
    dependsOn(
        lgResourcesPatch, nativeLibrariesPatch, nativeCallsPatch, databasePatch, lgFrameworkIdsPatch, lgMembersPatch,
        storagePatch, permissionScreenPatch, navigationBarPatch, usageLogPatch,
    )
    extendWith(LG_GALLERY_EXTENSION)
}
