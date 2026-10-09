package org.ungoogled.patches.maps.saved

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.maps.ui.activityContextHookPatch
import org.ungoogled.patches.maps.ui.customization.customizationScreenPatch
import org.ungoogled.patches.maps.ui.screenHostPatch
import org.ungoogled.patches.maps.ui.sharedExtensionPatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.ungoogled.patches.shared.addInstructionsAtLabel
import org.w3c.dom.Element
import org.ungoogled.patches.maps.microg.MicrogSelection

/** org.ungoogled.ui.SavedPlaces, the extension half. */
private const val SAVED_PLACES = "Lorg/ungoogled/ui/SavedPlaces;"
private const val OPEN_SCREEN = "Lorg/ungoogled/ui/SavedPlaces\$OpenScreen;"
private const val ACTIVITY = "org.ungoogled.ui.YouActivity"
/** drawable/gs_bookmark_vd_theme_24 in this build. */
private const val BOOKMARK_ICON = 0x7f080519
/** The Save button's own icons: drawable/ic_qu_placelist_heart and drawable/ic_suitcase (density split). */
private const val HEART_ICON = 0x7f080824
private const val SUITCASE_ICON = 0x7f080879

/** Declares the Local saved screen; it is launched by explicit class name from inside the app only. */
private val savedManifestPatch = resourcePatch(description = "Declares the Local saved screen.") {
    execute {
        // microG Maps has the Local saved screen too (see localSavedPlacesPatch).
        document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", ACTIVITY)
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:label", "Local saved")
            activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.DayNight")
            application.appendChild(activity)
        }
    }
}

/**
 * The saved-places controller: every Save button ends in its `r(place, flag)`,
 * which makes a signed-out user sign in first. The class is the only one with
 * this log message.
 */
private object SaveControllerFingerprint : Fingerprint(
    filters = listOf(string("Trying to edit dangling item [id=%s] without parent list.")),
)

/**
 * The quick-save path: with a Maps flag on, Save skips the list picker and calls `b(place)`
 * on this (R8-merged) class, which makes a signed-out user pick an account first. The
 * class is the only one with this log message.
 */
private object QuickSaveOwnerFingerprint : Fingerprint(
    filters = listOf(string("Failed to create SplitEngineRenderer, cannot launch Magic Window")),
)

/**
 * The Save button's state: its icon chooser, the only method returning both the suitcase and the
 * heart (filters match in bytecode order, and the suitcase's case comes first there).
 */
private object SaveButtonIconFingerprint : Fingerprint(
    returnType = "I",
    parameters = emptyList(),
    filters = listOf(literal(SUITCASE_ICON), literal(HEART_ICON)),
)

/** The place sheet's Call chip: its click is the only method with this trace label. */
private object CallClickFingerprint : Fingerprint(
    filters = listOf(string("OnCallClick")),
)

/** The place summary view model, whose Directions click routes to its place. Its trace label is unique. */
private object PlaceSummaryFingerprint : Fingerprint(
    filters = listOf(string("PlacemarkPlaceSummaryViewModel")),
)

/** string/ACCESSIBILITY_SHARE_PLACE ("Share %1${'$'}s"): only the place sheet's Share chip uses it. */
private const val SHARE_PLACE_A11Y = 0x7f1400cb

/** The place sheet's Share chip, by its content description. */
private object ShareLabelFingerprint : Fingerprint(
    filters = listOf(literal(SHARE_PLACE_A11Y)),
)

/** The place sheet's hero image view model, which reads the place's photos. Its trace label is unique. */
private object HeroImageFingerprint : Fingerprint(
    filters = listOf(string("PlacesheetHeroImageViewModelImpl")),
)

/** Maps' "Add a place" screen (a list's Add): the only class with this argument key. */
private object AddPlaceFingerprint : Fingerprint(
    filters = listOf(string("save-on-select")),
)

/** string/ADD_PLACE_TO_LIST_HINT ("Add a place"), the screen's search hint. */
private const val ADD_PLACE_HINT = 0x7f140166
/** ALIAS_SETTING_SIGN_IN_PROMPT: "Sign in to search for "%1$s" and your other personal places." */
private const val ALIAS_SIGN_IN_PROMPT = 0x7f1401fe
/** ALIASING_NEW_PLACE_SIGN_IN_PROMPT: "To label places and quickly find them on Maps, sign in." */
private const val LABEL_SIGN_IN_PROMPT = 0x7f1401e1

/** SavedPlaces.PICKED: a place picked on "Add a place" for one of the user's lists. */
private const val PICKED = 64

/** HistoryStore's kinds of use: a bit each. */
private const val VIEWED = 1
private const val DIRECTIONS = 2
private const val CALLED = 4
private const val SHARED = 8

/** `r` and `s`: make a progress dialog, wrap the place in a Runnable, run it once signed in. */
private val SAVE_ENTRY_SHAPE = listOf(
    Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.NEW_INSTANCE,
    Opcode.INVOKE_DIRECT, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID,
)

/** The "Local saved" row on the account sheet, right after Customization, in both of the sheet's builders. */
private fun BytecodePatchContext.addLocalSavedRow() {
    val rowHolder = mutableClassDefBy("Lolr;")
    val template = rowHolder.methods.single { it.name == "a" && it.returnType == "Lbrmi;" && it.parameterTypes.isEmpty() }
    val templateRefs = template.implementation!!.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
    listOf(
        "Lmm;->s(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;", "Lbrmi;->a()Lbrmg;",
        "Lbrmg;->c(I)V", "Lbrmg;->d(Ljava/lang/String;)V", "Lbrmg;->f(I)V",
        "Lbrmg;->e(Landroid/view/View\$OnClickListener;)V", "Lbrmf;->d:Lbrmf;", "Lbrmg;->a()Lbrmi;",
    ).forEach { if (it !in templateRefs) throw PatchException("account sheet row builder no longer uses $it") }
    // Its action id must be unique on the sheet (Maps throws "appears in more than one action"
    // otherwise), so it is generated rather than copied from the Customization row.
    rowHolder.methods.add(
        ImmutableMethod(
            rowHolder.type, "uaSavedRow", emptyList(), "Lbrmi;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, MutableMethodImplementation(6),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    iget-object v0, p0, Lolr;->a:Lnxb;
                    const v1, $BOOKMARK_ICON
                    invoke-static { v0, v1 }, Lmm;->s(Landroid/content/Context;I)Landroid/graphics/drawable/Drawable;
                    move-result-object v1
                    invoke-static { }, Lbrmi;->a()Lbrmg;
                    move-result-object v3
                    invoke-static { }, Landroid/view/View;->generateViewId()I
                    move-result v4
                    invoke-virtual { v3, v4 }, Lbrmg;->c(I)V
                    iput-object v1, v3, Lbrmg;->a:Landroid/graphics/drawable/Drawable;
                    const-string v4, "Local saved"
                    invoke-virtual { v3, v4 }, Lbrmg;->d(Ljava/lang/String;)V
                    const v4, 0x161a8
                    invoke-virtual { v3, v4 }, Lbrmg;->f(I)V
                    new-instance v2, $OPEN_SCREEN
                    invoke-direct { v2 }, $OPEN_SCREEN-><init>()V
                    invoke-virtual { v3, v2 }, Lbrmg;->e(Landroid/view/View${'$'}OnClickListener;)V
                    sget-object v4, Lbrmf;->d:Lbrmf;
                    iput-object v4, v3, Lbrmg;->d:Lbrmf;
                    invoke-virtual { v3 }, Lbrmg;->a()Lbrmi;
                    move-result-object v0
                    return-object v0
                """,
            )
        },
    )

    // Both sheet builders: find the Customization row's `add`, and add ours right after it.
    fun addAfterCustomization(returnType: String, parameters: List<String>) {
        val found = mutableListOf<Pair<String, String>>()
        classDefForEach { c ->
            for (m in c.methods) {
                if (m.returnType != returnType || m.parameterTypes.map { it.toString() } != parameters) continue
                val callsRow = m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r -> r.definingClass == "Lolr;" && r.name == "uaCustomizationRow" } == true
                } == true
                if (callsRow) found += c.type to m.name
            }
        }
        val (owner, name) = found.singleOrNull()
            ?: throw PatchException("account sheet builder $returnType(${parameters.joinToString()}) with the Customization row: found ${found.size}")
        val method = mutableClassDefBy(owner).methods.single {
            it.name == name && it.returnType == returnType && it.parameterTypes.map { t -> t.toString() } == parameters
        }
        val ins = method.implementation!!.instructions
        val call = ins.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r -> r.definingClass == "Lolr;" && r.name == "uaCustomizationRow" } == true
        }
        val result = ins[call + 1]
        val add = ins[call + 2]
        if (result.opcode != Opcode.MOVE_RESULT_OBJECT || add.opcode != Opcode.INVOKE_VIRTUAL ||
            ((add as ReferenceInstruction).reference as MethodReference).let { it.definingClass != "Lbwxy;" || it.name != "i" }
        ) throw PatchException("Customization row add changed shape")
        val scratch = (result as OneRegisterInstruction).registerA
        val list = (add as Instruction35c).registerC
        val holder = (ins[call] as Instruction35c).registerC
        // Modern builder: the holder is cast from a wider register just before; do the same.
        val cast = ins.getOrNull(call - 1)?.takeIf { it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == "Lolr;" }
        val source = cast?.let { (ins[call - 2] as TwoRegisterInstruction).registerB }
        method.addInstructions(
            call + 3,
            if (source != null) """
                move-object v$scratch, v$source
                check-cast v$scratch, Lolr;
                invoke-virtual { v$scratch }, Lolr;->uaSavedRow()Lbrmi;
                move-result-object v$scratch
                invoke-virtual { v$list, v$scratch }, Lbwxy;->i(Ljava/lang/Object;)V
            """ else """
                invoke-virtual { v$holder }, Lolr;->uaSavedRow()Lbrmi;
                move-result-object v$scratch
                invoke-virtual { v$list, v$scratch }, Lbwxy;->i(Ljava/lang/Object;)V
            """,
        )
    }
    addAfterCustomization("Lbrhh;", listOf("Lafmm;"))
    addAfterCustomization("Lbrfb;", listOf("Z"))
}

internal val localSavedPlacesPatch = bytecodePatch(
    description = "Save places without a Google account, kept only on the phone: Save opens Maps' own \"Place " +
        "saved\" sheet (Want to go, Travel plans, Starred places, Favorites, your own lists, a note), and a " +
        "\"Local saved\" row on the account sheet rebuilds Maps' You tab -- your recent places (looked at, " +
        "routed to, called, shared or saved), your lists and labels (Home, Work, your own) -- with export and " +
        "import (backup file, KML, Google Takeout's Saved Places.json).",
) {
    compatibleWith(COMPATIBILITY_MAPS)
    dependsOn(sharedExtensionPatch, activityContextHookPatch, screenHostPatch, customizationScreenPatch, savedManifestPatch)

    execute {
        // microG Maps saves to the Google account, as Maps does: it gets only the Local saved
        // screen, whose Pull from Google account copies the account's lists to the phone.
        val accountSaves = MicrogSelection.builds(this, "Offline saved places")
        // ---- what Maps' place object offers, read off the Save button's state class ----
        val stateClass = mutableClassDefBy(SaveButtonIconFingerprint.method.definingClass)
        val ctor = stateClass.methods.singleOrNull { it.name == "<init>" }
            ?: throw PatchException("Save button state class has more than one constructor")
        val placeType = ctor.parameterTypes.last().toString()
        val placeCalls = ctor.implementation!!.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.definingClass == placeType && it.parameterTypes.isEmpty() }
        // Two getters: the feature id and the position. The position type is the one whose
        // toString starts "lat/lng: (".
        fun printsLatLng(type: String) = classDefByOrNull(type)?.methods?.any { m ->
            m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "lat/lng: (" } == true
        } == true
        val positionGetter = placeCalls.singleOrNull { printsLatLng(it.returnType) }
            ?: throw PatchException("place position getter not found")
        val featureIdGetter = placeCalls.singleOrNull { it != positionGetter && it.returnType.startsWith("L") && !it.returnType.startsWith("Ljava/") }
            ?: throw PatchException("place feature id getter not found")
        val nameGetter = stateClass.methods.flatMap { m ->
            m.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.orEmpty()
        }.filter { it.definingClass == placeType && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() }
            .distinctBy { it.name }.singleOrNull() ?: throw PatchException("place name getter not found")
        val placeField = stateClass.fields.singleOrNull { it.type == placeType }
            ?: throw PatchException("Save button state holds no single place")

        // State and count: the two int fields; the state is the one compared with 1 by d().
        val intFields = stateClass.fields.filter { it.type == "I" && !AccessFlags.STATIC.isSet(it.accessFlags) }
        if (intFields.size != 2) throw PatchException("Save button state has ${intFields.size} int fields, expected 2")
        val isSaved = stateClass.methods.single { it.returnType == "Z" && it.parameterTypes.isEmpty() }
        val stateName = isSaved.implementation!!.instructions.firstNotNullOf {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { f -> f.type == "I" }?.name
        }
        val state = intFields.single { it.name == stateName }
        val count = intFields.single { it.name != stateName }

        if (accountSaves) {
            addLocalSavedRow()
            // A place imported from Google Takeout still takes its position from Maps when shown;
            // read only -- the Save button keeps the account's state.
            val last = ctor.implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
            if (last < 0 || ctor.implementation!!.instructions.count { it.opcode == Opcode.RETURN_VOID } != 1) {
                throw PatchException("Save button state constructor no longer has one return")
            }
            ctor.addInstructionsAtLabel(
                last,
                """
                    iget-object v0, p0, ${stateClass.type}->${placeField.name}:$placeType
                    invoke-virtual { v0 }, $placeType->${featureIdGetter.name}()${featureIdGetter.returnType}
                    move-result-object v0
                    iget-object v1, p0, ${stateClass.type}->${placeField.name}:$placeType
                    invoke-virtual { v1 }, $placeType->${positionGetter.name}()${positionGetter.returnType}
                    move-result-object v1
                    invoke-static { v0, v1 }, $SAVED_PLACES->placeShown(Ljava/lang/Object;Ljava/lang/Object;)V
                """,
            )
            return@execute
        }

        // ---- 1. Save: a single place goes to the extension's list picker -----------------
        val controller = mutableClassDefBy(SaveControllerFingerprint.method.definingClass)
        val activityField = controller.methods.single { it.returnType == "Landroid/app/ProgressDialog;" && it.parameterTypes.isEmpty() }
            .implementation!!.instructions.firstNotNullOf { ((it as? ReferenceInstruction)?.reference as? FieldReference) }
        val entries = controller.methods.filter { m ->
            m.returnType == "V" && m.parameterTypes.size == 2 && m.parameterTypes[1] == "Z" &&
                m.implementation?.instructions?.map { it.opcode } == SAVE_ENTRY_SHAPE
        }
        if (entries.size != 2) throw PatchException("expected the controller's two save entries, found ${entries.size}")
        entries.forEach { entry ->
            val refType = entry.parameterTypes[0].toString()
            // The reference's null-safe getter: static, takes the reference, returns its Serializable.
            val getter = classDefByOrNull(refType)?.methods?.singleOrNull { m ->
                AccessFlags.STATIC.isSet(m.accessFlags) && m.parameterTypes.map { it.toString() } == listOf(refType) &&
                    m.returnType == "Ljava/io/Serializable;"
            } ?: throw PatchException("$refType has no single static getter")
            if (entry.implementation!!.registerCount - 3 < 2) throw PatchException("save entry has too few locals")
            entry.addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $refType->${getter.name}($refType)Ljava/io/Serializable;
                    move-result-object v0
                    instance-of v1, v0, $placeType
                    if-eqz v1, :maps_save
                    check-cast v0, $placeType
                    invoke-virtual { v0 }, $placeType->${nameGetter.name}()Ljava/lang/String;
                    move-result-object v1
                    invoke-virtual { v0 }, $placeType->${featureIdGetter.name}()${featureIdGetter.returnType}
                    move-result-object p1
                    invoke-virtual { v0 }, $placeType->${positionGetter.name}()${positionGetter.returnType}
                    move-result-object p2
                    iget-object p0, p0, ${activityField.definingClass}->${activityField.name}:${activityField.type}
                    invoke-static { p0, v1, p1, p2 }, $SAVED_PLACES->save(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V
                    return-void
                """,
                ExternalLabel("maps_save", entry.implementation!!.instructions.first()),
            )
        }

        // ---- 1b. Quick save (Save's other route, behind a Maps flag): the same list picker ----
        val quickOwner = mutableClassDefBy(QuickSaveOwnerFingerprint.method.definingClass)
        val quick = quickOwner.methods.singleOrNull { m ->
            m.returnType == "V" && m.parameterTypes.map { it.toString() } == listOf(placeType)
        } ?: throw PatchException("quick save not found in ${quickOwner.type}")
        if (quick.implementation!!.registerCount - 2 < 3) throw PatchException("quick save has too few locals")
        quick.addInstructionsWithLabels(
            0,
            """
                if-eqz p1, :maps_quick_save
                invoke-virtual { p1 }, $placeType->${nameGetter.name}()Ljava/lang/String;
                move-result-object v0
                invoke-virtual { p1 }, $placeType->${featureIdGetter.name}()${featureIdGetter.returnType}
                move-result-object v1
                invoke-virtual { p1 }, $placeType->${positionGetter.name}()${positionGetter.returnType}
                move-result-object v2
                const/4 p0, 0x0
                invoke-static { p0, v0, v1, v2 }, $SAVED_PLACES->save(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V
                return-void
            """,
            ExternalLabel("maps_quick_save", quick.implementation!!.instructions.first()),
        )

        // ---- 2. The Save button says "Saved" for places saved here ------------------------
        //         (and a saved place from a Google Takeout list, which has no position, takes Maps')
        val end = ctor.implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
        if (end < 0 || ctor.implementation!!.instructions.count { it.opcode == Opcode.RETURN_VOID } != 1) {
            throw PatchException("Save button state constructor no longer has one return")
        }
        // At the return's label: the constructor's branches all end there.
        ctor.addInstructionsAtLabel(
            end,
            """
                iget-object v0, p0, ${stateClass.type}->${placeField.name}:$placeType
                invoke-virtual { v0 }, $placeType->${featureIdGetter.name}()${featureIdGetter.returnType}
                move-result-object v0
                iget v1, p0, ${stateClass.type}->${state.name}:I
                invoke-static { v0, v1 }, $SAVED_PLACES->buttonState(Ljava/lang/Object;I)I
                move-result v1
                iput v1, p0, ${stateClass.type}->${state.name}:I
                iget v1, p0, ${stateClass.type}->${count.name}:I
                invoke-static { v0, v1 }, $SAVED_PLACES->buttonCount(Ljava/lang/Object;I)I
                move-result v1
                iput v1, p0, ${stateClass.type}->${count.name}:I
                iget-object v1, p0, ${stateClass.type}->${placeField.name}:$placeType
                invoke-virtual { v1 }, $placeType->${positionGetter.name}()${positionGetter.returnType}
                move-result-object v1
                invoke-static { v0, v1 }, $SAVED_PLACES->placeShown(Ljava/lang/Object;Ljava/lang/Object;)V
            """,
        )

        // ---- 3. A "Local saved" row on the account sheet, right after Customization ---------
        addLocalSavedRow()

        // ---- 4. Save buttons redraw after a change made here -----------------------------
        // Maps works a button's saved state out once, when the place is bound. Each button
        // registers itself with the extension (SavedPlaces.trackButton) and gets uaRefresh(),
        // which the extension calls after the sheet closes.
        val invalidate = InvalidateFingerprint.method
        val invalidateRef = "${invalidate.definingClass}->${invalidate.name}(" +
            invalidate.parameterTypes.joinToString("") + ")${invalidate.returnType}"
        val refType = entries.first().parameterTypes[0].toString()
        fun makesState(m: com.android.tools.smali.dexlib2.iface.Method) = m.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.returnType == stateClass.type
        } == true
        // Framework buttons (the action row's Save): hold the state, and bind(place-ref) makes it.
        val bound = mutableListOf<Pair<String, String>>()
        // Compose headers (the bookmark icon): a no-argument method recomputes it through a helper.
        val recomputed = mutableListOf<Triple<String, String, Set<String>>>()
        // Framework view models that work the state out afresh whenever a view reads it -- the place
        // sheet's header bookmark (arqn.a(), read for its icon and its checked state; aqem.q() in
        // the older header), a transit station's menu: a redraw is all they need. They register
        // from those readers.
        val redrawn = mutableListOf<Pair<String, List<Pair<String, List<String>>>>>()
        val viewModelType = invalidate.parameterTypes.single().toString()
        fun isViewModel(type: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
            if (type == viewModelType) return true
            if (!seen.add(type)) return false
            val def = classDefByOrNull(type) ?: return false
            return def.superclass?.let { isViewModel(it, seen) } == true || def.interfaces.any { isViewModel(it, seen) }
        }
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/") || c.type == stateClass.type) return@classDefForEach
            // The methods here that work the state out; almost every class has none.
            val makers = c.methods.filter { makesState(it) }
            if (makers.isEmpty()) return@classDefForEach
            val bind = makers.firstOrNull { m ->
                m.returnType == "V" && m.parameterTypes.map { it.toString() } == listOf(refType)
            }
            if (bind != null && c.fields.any { it.type == stateClass.type }) {
                bound += c.type to bind.name
                return@classDefForEach
            }
            if (isViewModel(c.type)) {
                val readers = makers.filter { m -> m.name != "<init>" && !AccessFlags.STATIC.isSet(m.accessFlags) }
                if (readers.isNotEmpty()) {
                    redrawn += c.type to readers.map { m -> m.name to m.parameterTypes.map { it.toString() } }
                    return@classDefForEach
                }
            }
            val helpers = makers.filter { m ->
                m.returnType != "V" && m.parameterTypes.map { it.toString() } == listOf(placeType)
            }.map { it.name }.toSet()
            if (helpers.isEmpty()) return@classDefForEach
            val refresh = c.methods.firstOrNull { m ->
                m.returnType == "V" && m.parameterTypes.isEmpty() && m.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { r -> r.definingClass == c.type && r.name in helpers } == true
                } == true
            }
            if (refresh != null) recomputed += Triple(c.type, refresh.name, helpers)
        }
        if (bound.isEmpty() || recomputed.isEmpty() || redrawn.isEmpty()) {
            throw PatchException(
                "Save buttons to refresh not found (framework ${bound.size}, compose ${recomputed.size}, redrawn ${redrawn.size})",
            )
        }
        fun MutableMethodType.trackOnEntry() =
            // At entry p0 is still the button: arny.e() reuses its register before returning.
            addInstructionsAtLabel(0, "invoke-static { p0 }, $SAVED_PLACES->trackButton(Ljava/lang/Object;)V")
        bound.forEach { (type, bindName) ->
            // The place reference the bind is given, kept in the class or a superclass.
            val holder = generateSequence(classDefByOrNull(type)) { c -> c.superclass?.let { classDefByOrNull(it) } }
                .flatMap { it.fields.asSequence() }.firstOrNull { it.type == refType } ?: return@forEach
            val cls = mutableClassDefBy(type)
            cls.methods.single { it.name == bindName && it.parameterTypes.map { p -> p.toString() } == listOf(refType) }.trackOnEntry()
            cls.methods.add(
                ImmutableMethod(type, "uaRefresh", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null, null, MutableMethodImplementation(2)).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, ${holder.definingClass}->${holder.name}:$refType
                            if-eqz v0, :none
                            invoke-virtual { p0, v0 }, $type->$bindName($refType)V
                            invoke-static { p0 }, $invalidateRef
                            :none
                            return-void
                        """,
                    )
                },
            )
        }
        recomputed.forEach { (type, refreshName, helpers) ->
            val cls = mutableClassDefBy(type)
            cls.methods.single { it.name == refreshName && it.parameterTypes.isEmpty() && it.returnType == "V" }.trackOnEntry()
            // The header works its first state out in its constructor, through the helper, and Maps
            // never calls the refresh for a place just opened: unless it registers there as well it
            // is not redrawn after an Unsave, and keeps its "saved" check.
            cls.methods.filter {
                it.name in helpers && !AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.parameterTypes.map { p -> p.toString() } == listOf(placeType)
            }.forEach { it.trackOnEntry() }
            cls.methods.add(
                ImmutableMethod(type, "uaRefresh", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null, null, MutableMethodImplementation(1)).toMutable().apply {
                    addInstructions(0, "invoke-virtual { p0 }, $type->$refreshName()V\nreturn-void")
                },
            )
        }
        redrawn.forEach { (type, readers) ->
            val cls = mutableClassDefBy(type)
            readers.forEach { (name, params) ->
                cls.methods.filter { it.name == name && it.parameterTypes.map { p -> p.toString() } == params }
                    .forEach { it.trackOnEntry() }
            }
            cls.methods.add(
                ImmutableMethod(type, "uaRefresh", emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null, null, MutableMethodImplementation(1)).toMutable().apply {
                    addInstructions(0, "invoke-static { p0 }, $invalidateRef\nreturn-void")
                },
            )
        }

        // ---- 5. Your recent places: the places looked at, routed to, called and shared --------
        // Maps keeps these as the Google account's Maps history. A static helper on Maps' place
        // class hands the place -- name, id, position, category line and first photo, as Maps' own
        // You tab shows them -- and the kind of use to the extension (SavedPlaces.interacted).
        val summary = mutableClassDefBy(PlaceSummaryFingerprint.method.definingClass)
        fun refs(m: com.android.tools.smali.dexlib2.iface.Method) =
            m.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference }.orEmpty()
        // The category line: the summary's static (place, Activity) -> String shows the place's
        // category getter last (after a special case for some places).
        val categoryGetter = summary.methods.singleOrNull { m ->
            AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "Ljava/lang/String;" &&
                m.parameterTypes.map { it.toString() } == listOf(placeType, "Landroid/app/Activity;")
        }?.let { m ->
            refs(m).filterIsInstance<MethodReference>()
                .lastOrNull { it.definingClass == placeType && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() }
        } ?: throw PatchException("place category getter not found")
        // A photo's image URL: the summary's static photo -> image-reference helper reads one String field.
        val photoUrl = summary.methods.mapNotNull { m ->
            if (!AccessFlags.STATIC.isSet(m.accessFlags) || m.parameterTypes.size != 1) return@mapNotNull null
            val photo = m.parameterTypes.single().toString()
            if (photo == placeType || !photo.startsWith("L") || photo.startsWith("Ljava/")) return@mapNotNull null
            val fields = refs(m).filterIsInstance<FieldReference>().filter { it.definingClass == photo }
            fields.singleOrNull()?.takeIf { it.type == "Ljava/lang/String;" && refs(m).any { r -> r is TypeReference && r.type == m.returnType } }
        }.distinct().singleOrNull() ?: throw PatchException("place photo URL field not found")
        // The photos themselves: the list the place sheet's hero image reads off the place first.
        val hero = classDefByOrNull(HeroImageFingerprint.method.definingClass)
            ?: throw PatchException("place sheet hero image not found")
        val photosGetter = hero.methods.filter { m -> m.returnType == "V" && m.parameterTypes.map { it.toString() } == listOf(placeType) }
            .firstNotNullOfOrNull { m ->
                refs(m).filterIsInstance<MethodReference>()
                    .firstOrNull { it.definingClass == placeType && it.returnType == "Ljava/util/List;" && it.parameterTypes.isEmpty() }
            } ?: throw PatchException("place photos getter not found")
        // The rating: what the summary's float accessor returns from the place. The review count:
        // the place's one int getter reading the same rating record.
        val ratingGetter = summary.methods.filter { it.returnType == "F" && it.parameterTypes.isEmpty() }
            .flatMap { m -> refs(m).filterIsInstance<MethodReference>() }
            .distinct().singleOrNull { it.definingClass == placeType && it.returnType == "F" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("place rating getter not found")
        val placeDef = classDefByOrNull(placeType) ?: throw PatchException("place class not found")
        val ratingRecord = placeDef.methods.single { it.name == ratingGetter.name && it.returnType == "F" && it.parameterTypes.isEmpty() }
            .let { m -> refs(m).filterIsInstance<MethodReference>().first { it.definingClass == placeType && it.parameterTypes.isEmpty() && it.returnType.startsWith("L") } }
        val reviewsGetter = placeDef.methods.singleOrNull { m ->
            m.returnType == "I" && m.parameterTypes.isEmpty() && refs(m).any { it == ratingRecord } &&
                refs(m).any { r -> r is FieldReference && r.definingClass == ratingRecord.returnType && r.type == "I" }
        } ?: throw PatchException("place review count getter not found")

        val placeClass = mutableClassDefBy(placeType)
        placeClass.methods.add(
            ImmutableMethod(
                placeType, "uaInteract",
                listOf(ImmutableMethodParameter(placeType, null, null), ImmutableMethodParameter("I", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(11),
            ).toMutable().apply {
                // The photos go over as Maps' list, with the name of the URL field their class keeps it in.
                addInstructions(
                    0,
                    """
                        if-eqz p0, :none
                        invoke-virtual { p0 }, $placeType->${nameGetter.name}()Ljava/lang/String;
                        move-result-object v0
                        invoke-virtual { p0 }, $placeType->${featureIdGetter.name}()${featureIdGetter.returnType}
                        move-result-object v1
                        invoke-virtual { p0 }, $placeType->${positionGetter.name}()${positionGetter.returnType}
                        move-result-object v2
                        invoke-virtual { p0 }, $placeType->${categoryGetter.name}()Ljava/lang/String;
                        move-result-object v3
                        invoke-virtual { p0 }, $placeType->${photosGetter.name}()Ljava/util/List;
                        move-result-object v4
                        const-string v5, "${photoUrl.name}"
                        invoke-virtual { p0 }, $placeType->${ratingGetter.name}()F
                        move-result v6
                        invoke-virtual { p0 }, $placeType->${reviewsGetter.name}()I
                        move-result v7
                        move v8, p1
                        invoke-static/range { v0 .. v8 }, $SAVED_PLACES->interacted(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;FII)V
                        :none
                        return-void
                    """,
                )
            },
        )
        val interact = "$placeType->uaInteract(${placeType}I)V"
        fun MutableMethodType.locals() = implementation!!.registerCount - 1 -
            parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2L else 1L }.toInt()
        /** The place getter a chip calls (avhi's x()). */
        fun MutableMethodType.placeGetter() = implementation!!.instructions.firstNotNullOfOrNull {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)
                ?.takeIf { r -> r.returnType == placeType && r.parameterTypes.isEmpty() }
        } ?: throw PatchException("$definingClass->$name no longer reads its place")

        // Viewed: the place sheet's Save chip binds its place (bound above); at the end of that bind.
        bound.forEach { (type, bindName) ->
            val bind = mutableClassDefBy(type).methods.single {
                it.name == bindName && it.parameterTypes.map { p -> p.toString() } == listOf(refType)
            }
            val returns = bind.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
            if (returns.size != 1 || bind.implementation!!.registerCount < 3) throw PatchException("Save chip bind changed shape")
            val getter = bind.placeGetter()
            // v0 and the place-reference parameter are both free once the bind is done.
            bind.addInstructionsAtLabel(
                returns.single().index,
                """
                    invoke-virtual { p0 }, ${getter.definingClass}->${getter.name}()$placeType
                    move-result-object v0
                    const/16 p1, $VIEWED
                    invoke-static { v0, p1 }, $interact
                """,
            )
        }

        // Got directions: the summary's Directions click, the (click)V method that builds a route
        // from its place (a static call taking the place first); at entry, before it routes.
        val callClick = CallClickFingerprint.method
        val clickParams = callClick.parameterTypes.map { it.toString() }
        val directionsClick = summary.methods.singleOrNull { m ->
            m.returnType == "V" && m.parameterTypes.map { it.toString() } == clickParams &&
                m.implementation?.instructions?.any { i ->
                    i.opcode == Opcode.INVOKE_STATIC &&
                        ((i as ReferenceInstruction).reference as MethodReference).parameterTypes.firstOrNull()?.toString() == placeType
                } == true
        } ?: throw PatchException("Directions click not found in ${summary.type}")
        val summaryPlace = directionsClick.implementation!!.instructions.firstNotNullOfOrNull {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { f -> f.type == placeType && f.definingClass == summary.type }
        } ?: throw PatchException("Directions click no longer reads its place")
        if (directionsClick.locals() < 2) throw PatchException("Directions click has no room for the hook")
        directionsClick.addInstructionsAtLabel(
            0,
            """
                iget-object v0, p0, ${summaryPlace.definingClass}->${summaryPlace.name}:$placeType
                const/16 v1, $DIRECTIONS
                invoke-static { v0, v1 }, $interact
            """,
        )

        // Called and Shared: the Call and Share chips' clicks, at entry, with their place.
        fun hookChipClick(click: MutableMethodType, kind: Int) {
            if (click.locals() < 2) throw PatchException("${click.definingClass} click has no room for the hook")
            val getter = click.placeGetter()
            click.addInstructionsAtLabel(
                0,
                """
                    invoke-virtual { p0 }, ${getter.definingClass}->${getter.name}()$placeType
                    move-result-object v0
                    const/16 v1, $kind
                    invoke-static { v0, v1 }, $interact
                """,
            )
        }
        val callChip = mutableClassDefBy(callClick.definingClass)
        hookChipClick(callChip.methods.single { it.name == callClick.name && it.parameterTypes.map { p -> p.toString() } == clickParams }, CALLED)
        val shareChip = mutableClassDefBy(ShareLabelFingerprint.method.definingClass)
        hookChipClick(
            shareChip.methods.singleOrNull {
                it.name == callClick.name && it.returnType == callClick.returnType &&
                    it.parameterTypes.map { p -> p.toString() } == clickParams
            } ?: throw PatchException("Share chip click not found in ${shareChip.type}"),
            SHARED,
        )

        // ---- 6. Add on a list: Maps' own "Add a place" screen --------------------------------
        // Maps opens it for a Google account's list; here SavedPlaces.showAddPlace builds it the
        // same way -- the screen's factory, the component's bundle helper, the activity's show --
        // and its pick goes into the user's list instead.
        val picker = mutableClassDefBy(AddPlaceFingerprint.method.definingClass)
        val factory = picker.methods.singleOrNull { m ->
            AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == picker.type &&
                m.parameterTypes.map { it.toString() }.let { it.size == 4 && it.drop(1) == listOf("Z", "Ljava/lang/String;", "Z") }
        } ?: throw PatchException("Add a place factory not found")
        val helperType = factory.parameterTypes.first().toString()
        val pick = picker.methods.singleOrNull { m ->
            m.returnType == "V" && m.parameterTypes.map { it.toString() } == listOf(placeType)
        } ?: throw PatchException("Add a place pick not found")
        val close = pick.implementation!!.instructions.filter { it.opcode == Opcode.INVOKE_STATIC }
            .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
            .lastOrNull { it.parameterTypes.size == 1 && it.returnType == "V" } ?: throw PatchException("Add a place close not found")
        // The component interface handing out the bundle helper, the locator Maps asks for it, and
        // the activity method Maps shows the screen with (called on the factory's result).
        // Several component interfaces hand the helper out; any one Maps looks up works.
        val providers = mutableMapOf<String, String>()
        classDefForEach { c ->
            if (AccessFlags.INTERFACE.isSet(c.accessFlags) && c.methods.count() == 1 &&
                c.methods.single().let { it.returnType == helperType && it.parameterTypes.isEmpty() }
            ) providers[c.type] = c.methods.single().name
        }
        fun isActivity(type: String): Boolean {
            var t: String? = type
            repeat(12) {
                if (t == "Landroid/app/Activity;") return true
                t = t?.let { classDefByOrNull(it)?.superclass } ?: return false
            }
            return false
        }
        var providerType: String? = null
        var locator: MethodReference? = null
        var show: MethodReference? = null
        classDefForEach { c ->
            if (locator != null && show != null) return@classDefForEach
            for (m in c.methods) {
                val ins = m.implementation?.instructions?.toList() ?: continue
                ins.forEachIndexed { i, inst ->
                    val ref = (inst as? ReferenceInstruction)?.reference
                    val constClass = (ref as? TypeReference)?.type
                    if (locator == null && inst.opcode == Opcode.CONST_CLASS && constClass in providers) {
                        locator = ins.drop(i + 1).take(3).firstNotNullOfOrNull { n ->
                            ((n as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                                n.opcode == Opcode.INVOKE_STATIC && it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/Class;")
                            }
                        }
                        if (locator != null) providerType = constClass
                    }
                    if (show == null && inst.opcode == Opcode.INVOKE_STATIC && (ref as? MethodReference)?.let {
                            it.definingClass == picker.type && it.name == factory.name && it.parameterTypes.size == 4
                        } == true
                    ) {
                        // Shown by the activity itself (a list editor's own launcher would take a fragment).
                        show = ins.drop(i + 1).take(3).firstNotNullOfOrNull { n ->
                            ((n as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                                n.opcode == Opcode.INVOKE_VIRTUAL && it.parameterTypes.size == 1 && it.returnType == "V" &&
                                    isActivity(it.definingClass)
                            }
                        }
                    }
                }
            }
        }
        val locate = locator ?: throw PatchException("component locator not found")
        val provider = providerType!!
        val providerMethod = providers.getValue(provider)
        val shown = show ?: throw PatchException("Add a place show not found")
        val extension = mutableClassDefBy(SAVED_PLACES)
        extension.methods.remove(extension.methods.single { it.name == "showAddPlace" })
        extension.methods.add(
            ImmutableMethod(
                SAVED_PLACES, "showAddPlace", listOf(ImmutableMethodParameter("Landroid/app/Activity;", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(5),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        const-class v0, $provider
                        invoke-static { v0 }, ${locate.definingClass}->${locate.name}(Ljava/lang/Class;)${locate.returnType}
                        move-result-object v0
                        check-cast v0, $provider
                        invoke-interface { v0 }, $provider->$providerMethod()$helperType
                        move-result-object v0
                        const v1, $ADD_PLACE_HINT
                        invoke-virtual { p0, v1 }, Landroid/app/Activity;->getString(I)Ljava/lang/String;
                        move-result-object v2
                        const/4 v1, 0x0
                        invoke-static { v0, v1, v2, v1 }, ${picker.type}->${factory.name}(${helperType}ZLjava/lang/String;Z)${picker.type}
                        move-result-object v0
                        check-cast p0, ${shown.definingClass}
                        invoke-virtual { p0, v0 }, ${shown.definingClass}->${shown.name}(${shown.parameterTypes.single()})V
                        return-void
                    """,
                )
            },
        )
        // Its pick: the place goes to the extension first; if it went into the user's list, close.
        if (pick.locals() < 1) throw PatchException("Add a place pick has no room for the hook")
        pick.addInstructionsWithLabels(
            0,
            """
                const/16 v0, $PICKED
                invoke-static { p1, v0 }, $interact
                invoke-static { }, $SAVED_PLACES->pickedIntoList()Z
                move-result v0
                if-eqz v0, :maps_pick
                invoke-static { p0 }, ${close.definingClass}->${close.name}(${close.parameterTypes.single()})V
                return-void
            """,
            ExternalLabel("maps_pick", pick.implementation!!.instructions.first()),
        )

        // ---- 7. Home and Work searched in Maps' own search box (issue #36) ----------------
        personalPlaceSearch()

        // ---- 8. Maps' own "Add label" labels the place in Local saved (issue #36) ----------
        localLabels(placeType, nameGetter, featureIdGetter, positionGetter)

        // ---- 9. The search box opens the user's labels -- Home, Work, their own (issue #36) --
        SearchSubmitFingerprint.method.apply {
            if (parameterTypes.firstOrNull()?.toString() != "Ljava/lang/String;" || returnType != "V") {
                throw PatchException("search submit no longer takes the query first")
            }
            if (implementation!!.registerCount - parameterTypes.size - 1 < 1) throw PatchException("search submit has no room for the hook")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $SAVED_PLACES->searchLabel(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :maps_search
                    return-void
                """,
                ExternalLabel("maps_search", implementation!!.instructions.first()),
            )
        }

        // ---- 10. Directions to the user's labels: "Home" as a destination (issue #36) --------
        labelWaypoints(featureIdGetter.returnType, positionGetter.returnType)
    }
}

private typealias MutableMethodType = app.morphe.patcher.util.proxy.mutableTypes.MutableMethod

/** The UI framework's invalidate(viewModel): redraws every view bound to it. Its log label is unique. */
private object InvalidateFingerprint : Fingerprint(
    filters = listOf(string("VPB.invalidate ")),
)

/** The search box's submit (query, ...), SearchSuggestFragment.onQueryTextSubmit: its trace label is unique. */
private object SearchSubmitFingerprint : Fingerprint(
    filters = listOf(string("SearchSuggestFragment.onQueryTextSubmit")),
)

/**
 * Searching "Home" or "Work", Maps' server answers with a personal-place block, and Maps shows one
 * of three dialogs over the map: sign in, turn on history, or set the address -- Home and Work live
 * in the Google account. Signed out it is always "sign in", even with Home set in Local saved
 * (issue #36). Each dialog's show now goes through SavedPlaces.showAliasDialog, which opens the
 * local Home or Work instead; the block is handed over just before the dialog's arguments are built.
 */
private fun BytecodePatchContext.personalPlaceSearch() {
    fun Instruction.method() = (this as? ReferenceInstruction)?.reference as? MethodReference
    // The sign-in dialog's view model: the class that names the sign-in prompt.
    val promptOwners = mutableListOf<String>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        val names = c.methods.any { m ->
            m.implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral == ALIAS_SIGN_IN_PROMPT.toLong() } == true
        }
        if (names) promptOwners += c.type
    }
    val viewModel = promptOwners.singleOrNull()
        ?: throw PatchException("expected one view model naming the personal place sign-in prompt, found $promptOwners")
    // Its base class reads the block's first entry: block.list.get(0) as entry, entry.place, place.kind.
    val base = classDefByOrNull(viewModel)?.superclass ?: throw PatchException("$viewModel has no base class")
    val reader = classDefByOrNull(base)!!.methods.singleOrNull { m ->
        m.implementation?.instructions?.any { it.method()?.let { r -> r.definingClass == "Ljava/util/List;" && r.name == "get" } == true } == true
    } ?: throw PatchException("$base no longer reads one personal place")
    val code = reader.implementation!!.instructions.toList()
    val get = code.indexOfFirst { it.method()?.let { r -> r.definingClass == "Ljava/util/List;" && r.name == "get" } == true }
    val listField = code.subList(0, get).lastOrNull { it.opcode == Opcode.IGET_OBJECT }?.let { (it as ReferenceInstruction).reference as FieldReference }
        ?: throw PatchException("personal place list field not found")
    val entryType = code.subList(get, code.size).firstOrNull { it.opcode == Opcode.CHECK_CAST }?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
        ?: throw PatchException("personal place entry type not found")
    val placeField = code.firstOrNull { it.opcode == Opcode.IGET_OBJECT && ((it as ReferenceInstruction).reference as FieldReference).definingClass == entryType }
        ?.let { (it as ReferenceInstruction).reference as FieldReference } ?: throw PatchException("personal place field not found")
    val kindField = code.firstOrNull { it.opcode == Opcode.IGET && ((it as ReferenceInstruction).reference as FieldReference).definingClass == placeField.type }
        ?.let { (it as ReferenceInstruction).reference as FieldReference } ?: throw PatchException("personal place kind field not found")
    val blockType = listField.definingClass

    // The sign-in dialog, and the one method that shows it: the search answer's handler.
    fun Instruction.creates(type: String) =
        opcode == Opcode.NEW_INSTANCE && ((this as ReferenceInstruction).reference as TypeReference).type == type
    val dialogs = mutableListOf<String>()
    classDefForEach { c ->
        if (c.methods.any { m -> m.implementation?.instructions?.any { it.creates(viewModel) } == true }) dialogs += c.type
    }
    val signInDialog = dialogs.singleOrNull() ?: throw PatchException("expected one personal place sign-in dialog, found $dialogs")
    val handlers = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
    classDefForEach { c ->
        for (m in c.methods) if (m.implementation?.instructions?.any { it.creates(signInDialog) } == true) handlers += c.type to m
    }
    val (handlerType, found) = handlers.singleOrNull() ?: throw PatchException("expected one search answer handler, found ${handlers.size}")
    val handler = mutableClassDefBy(handlerType).methods.single {
        it.name == found.name && it.parameterTypes == found.parameterTypes && it.returnType == found.returnType
    }
    val ins = handler.implementation!!.instructions.toList()

    // Each dialog: arguments built from (helper, block, callback), set, then the dialog shown.
    class Dialog(val build: Int, val block: Int, val show: Int, val dialog: Int, val activity: Int, val showName: String)
    val shown = ins.indices.filter { i ->
        ins[i].opcode == Opcode.INVOKE_STATIC && ins[i].method()?.let { r ->
            r.returnType == "Landroid/os/Bundle;" && r.parameterTypes.map(CharSequence::toString).contains(blockType)
        } == true
    }.map { b ->
        val build = ins[b] as Instruction35c
        val position = build.reference.let { (it as MethodReference).parameterTypes.map(CharSequence::toString).indexOf(blockType) }
        val block = listOf(build.registerC, build.registerD, build.registerE, build.registerF, build.registerG)[position]
        val setArguments = (b + 1 until ins.size).firstOrNull { j ->
            ins[j].opcode == Opcode.INVOKE_VIRTUAL && ins[j].method()?.parameterTypes?.map(CharSequence::toString) == listOf("Landroid/os/Bundle;")
        } ?: throw PatchException("personal place dialog arguments are not set")
        val dialog = (ins[setArguments] as Instruction35c).registerC
        val show = (setArguments + 1 until ins.size).firstOrNull { k ->
            ins[k].opcode == Opcode.INVOKE_VIRTUAL && (ins[k] as Instruction35c).let { it.registerCount == 2 && it.registerC == dialog } &&
                ins[k].method()?.let { it.returnType == "V" && it.parameterTypes.single().toString() != "Landroid/os/Bundle;" } == true
        } ?: throw PatchException("personal place dialog is not shown")
        val call = ins[show] as Instruction35c
        if (ins[b].location.labels.isNotEmpty()) throw PatchException("personal place dialog arguments are a branch target")
        if (block > 15 || call.registerC > 15 || call.registerD > 15) throw PatchException("personal place dialog registers out of range")
        Dialog(b, block, show, call.registerC, call.registerD, ins[show].method()!!.name)
    }
    if (shown.size != 3) throw PatchException("expected three personal place dialogs, found ${shown.size}")
    val showName = shown.map { it.showName }.distinct().singleOrNull()
        ?: throw PatchException("personal place dialogs are shown by different methods: ${shown.map { it.showName }}")
    if (shown.sortedBy { it.build }.zipWithNext().any { (a, b) -> a.show >= b.build }) {
        throw PatchException("personal place dialogs overlap in ${handler.definingClass}")
    }
    // Later dialogs first, so the earlier indices stay put.
    for (d in shown.sortedByDescending { it.build }) {
        handler.replaceInstruction(
            d.show,
            "invoke-static { v${d.dialog}, v${d.activity} }, $SAVED_PLACES->showAliasDialog(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
        handler.addInstructions(d.build, "invoke-static { v${d.block} }, $SAVED_PLACES->aliasAnswer(Ljava/lang/Object;)V")
    }

    // The extension reads the block's kind and calls Maps' show by these.
    val extension = mutableClassDefBy(SAVED_PLACES)
    extension.methods.remove(extension.methods.single { it.name == "aliasKind" })
    extension.methods.add(
        ImmutableMethod(
            SAVED_PLACES, "aliasKind", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "I",
            AccessFlags.STATIC.value, null, null, MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    check-cast p0, $blockType
                    iget-object v0, p0, $blockType->${listField.name}:${listField.type}
                    const/4 v1, 0x0
                    invoke-interface { v0, v1 }, Ljava/util/List;->get(I)Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, $entryType
                    iget-object v0, v0, $entryType->${placeField.name}:${placeField.type}
                    iget v0, v0, ${placeField.type}->${kindField.name}:I
                    return v0
                """,
            )
        },
    )
    extension.methods.single { it.name == "showMethod" && it.parameterTypes.isEmpty() }.apply {
        val first = implementation!!.instructions.first()
        if (first.opcode != Opcode.CONST_STRING) throw PatchException("SavedPlaces.showMethod() no longer starts with const-string")
        replaceInstruction(0, "const-string v${(first as OneRegisterInstruction).registerA}, \"$showName\"")
    }
}

/**
 * The directions waypoint editors -- two, one per directions screen -- build a waypoint from the typed
 * query alone, so signed out "Home" found nothing ("Something went wrong"): Home and Work live in the
 * Google account (issue #36). Right after each puts the query on its waypoint builder,
 * SavedPlaces.labelWaypoint turns a query naming one of the user's labels into that place, built as
 * Maps builds its own "Home" waypoint: position and feature id, the label as its name, no query.
 */
private fun BytecodePatchContext.labelWaypoints(featureType: String, positionType: String) {
    fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
    val editors = mutableListOf<Pair<String, com.android.tools.smali.dexlib2.iface.Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.parameterTypes.firstOrNull()?.toString() != "Ljava/lang/String;") continue
            if (m.implementation?.instructions?.any { (it.ref() as? StringReference)?.string == "DirectionsWaypointEditorQueryEntered" } == true) {
                editors += c.type to m
            }
        }
    }
    if (editors.size != 2) throw PatchException("expected two directions waypoint editors, found ${editors.map { it.first }}")
    val builders = editors.map { (type, found) ->
        val method = mutableClassDefBy(type).methods.single {
            it.name == found.name && it.parameterTypes == found.parameterTypes && it.returnType == found.returnType
        }
        // The query is the first parameter: p1, after this.
        val query = method.implementation!!.registerCount -
            method.parameterTypes.map(CharSequence::toString).sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
        val code = method.implementation!!.instructions.toList()
        val stores = code.indices.filter { i ->
            code[i].opcode == Opcode.IPUT_OBJECT && (code[i] as TwoRegisterInstruction).registerA == query &&
                (code[i].ref() as FieldReference).type == "Ljava/lang/String;"
        }
        val at = stores.singleOrNull() ?: throw PatchException("$type puts the query on ${stores.size} fields")
        val builder = (code[at] as TwoRegisterInstruction).registerB
        if (code[at + 1].location.labels.isNotEmpty()) throw PatchException("$type's query store is followed by a branch target")
        if (builder > 15 || query > 15) throw PatchException("$type's waypoint registers are out of range")
        method.addInstructions(
            at + 1,
            "invoke-static { v$builder, v$query }, $SAVED_PLACES->labelWaypoint(Ljava/lang/Object;Ljava/lang/String;)V",
        )
        (code[at].ref() as FieldReference).let { "${it.definingClass}->${it.name}" }
    }
    val queryRef = builders.distinct().singleOrNull() ?: throw PatchException("waypoint editors build different waypoints: $builders")
    val builder = queryRef.substringBefore("->")
    val queryField = queryRef.substringAfter("->")
    val builderClass = classDefByOrNull(builder) ?: throw PatchException("$builder not found")
    // Its name: the field Maps' own "Home" waypoint puts the literal "Home" in.
    val named = mutableSetOf<String>()
    classDefForEach { c ->
        for (m in c.methods) {
            val code = m.implementation?.instructions?.toList() ?: continue
            for (i in code.indices) {
                if (code[i].opcode != Opcode.IPUT_OBJECT) continue
                val f = code[i].ref() as FieldReference
                if (f.definingClass != builder || f.type != "Ljava/lang/String;") continue
                val value = (code[i] as TwoRegisterInstruction).registerA
                val source = (i - 1 downTo maxOf(0, i - 40)).firstOrNull { j ->
                    code[j].opcode.setsRegister() && (code[j] as? OneRegisterInstruction)?.registerA == value
                } ?: continue
                if ((code[source].ref() as? StringReference)?.string == "Home") named += f.name
            }
        }
    }
    val nameField = named.singleOrNull() ?: throw PatchException("$builder's name field is not clear: $named")
    val featureField = builderClass.fields.singleOrNull { it.type == featureType && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: throw PatchException("$builder has no single feature id field")
    // Its position: of its fields of that type, the one Maps sets when it builds a waypoint for a place.
    val writes = builderClass.fields.filter { it.type == positionType && !AccessFlags.STATIC.isSet(it.accessFlags) }
        .associate { it.name to 0 }.toMutableMap()
    classDefForEach { c ->
        if (c.type == builder) return@classDefForEach
        for (m in c.methods) m.implementation?.instructions?.forEach { insn ->
            if (insn.opcode != Opcode.IPUT_OBJECT) return@forEach
            val f = insn.ref() as FieldReference
            if (f.definingClass == builder && f.name in writes) writes[f.name] = writes.getValue(f.name) + 1
        }
    }
    val ranked = writes.entries.sortedByDescending { it.value }
    if (ranked.isEmpty() || (ranked.size > 1 && ranked[0].value < 2 * ranked[1].value)) {
        throw PatchException("$builder's position field is not clear: $writes")
    }
    val positionField = ranked[0].key
    fun publicInit(type: String, params: List<String>) = classDefByOrNull(type)?.methods?.any {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == params && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } == true
    if (!publicInit(featureType, listOf("J", "J"))) throw PatchException("$featureType has no public (long, long) constructor")
    if (!publicInit(positionType, listOf("D", "D"))) throw PatchException("$positionType has no public (double, double) constructor")

    // The extension's setters: a new value of that type put on the builder's field, and the name.
    val extension = mutableClassDefBy(SAVED_PLACES)
    fun setter(name: String, wide: String, type: String, field: String) {
        extension.methods.remove(extension.methods.single { it.name == name })
        extension.methods.add(
            ImmutableMethod(
                SAVED_PLACES, name,
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null), ImmutableMethodParameter(wide, null, null), ImmutableMethodParameter(wide, null, null)),
                "V", AccessFlags.STATIC.value, null, null, MutableMethodImplementation(6),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        check-cast p0, $builder
                        new-instance v0, $type
                        invoke-direct { v0, p1, p2, p3, p4 }, $type-><init>($wide$wide)V
                        iput-object v0, p0, $builder->$field:$type
                        return-void
                    """,
                )
            },
        )
    }
    setter("setWaypointFeature", "J", featureType, featureField.name)
    setter("setWaypointPosition", "D", positionType, positionField)
    extension.methods.remove(extension.methods.single { it.name == "setWaypointName" })
    extension.methods.add(
        ImmutableMethod(
            SAVED_PLACES, "setWaypointName",
            listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null), ImmutableMethodParameter("Ljava/lang/String;", null, null)),
            "V", AccessFlags.STATIC.value, null, null, MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    check-cast p0, $builder
                    iput-object p1, p0, $builder->$nameField:Ljava/lang/String;
                    const/4 v0, 0x0
                    iput-object v0, p0, $builder->$queryField:Ljava/lang/String;
                    return-void
                """,
            )
        },
    )
}

/**
 * Maps' "Add label" -- on the place sheet's More, and in the older overflow menu -- labels the place
 * in Local saved: Maps keeps labels in the Google account, so signed out both only asked to sign in
 * (issue #36). Each menu builds its own subclass of one prompt, whose gate opens Maps' label editor
 * when signed in and the sign-in prompt otherwise. Each subclass gets uaLabel(activity), which reads
 * the place where its own editor opener does and hands it to SavedPlaces.label, and the gate asks it
 * first. "Add to contacts" -- the same prompt with its contact flag set -- keeps Maps' own.
 */
private fun BytecodePatchContext.localLabels(
    placeType: String, nameGetter: MethodReference, featureIdGetter: MethodReference, positionGetter: MethodReference,
) {
    fun Instruction.ref() = (this as? ReferenceInstruction)?.reference
    // The prompt's text: the one class naming "To label places ... sign in".
    val texts = mutableListOf<String>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        if (c.methods.any { m -> m.implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral == LABEL_SIGN_IN_PROMPT.toLong() } == true }) {
            texts += c.type
        }
    }
    val text = texts.singleOrNull() ?: throw PatchException("expected one label sign-in prompt, found $texts")
    // The prompts: the classes that build that text, one per menu.
    val prompts = mutableListOf<String>()
    classDefForEach { c ->
        if (c.methods.any { m -> m.implementation?.instructions?.any { it.opcode == Opcode.NEW_INSTANCE && (it.ref() as TypeReference).type == text } == true }) {
            prompts += c.type
        }
    }
    if (prompts.size != 2) throw PatchException("expected two label prompts, found $prompts")
    val baseType = prompts.map { classDefByOrNull(it)?.superclass }.distinct().singleOrNull()
        ?: throw PatchException("label prompts $prompts have different base classes")
    val base = mutableClassDefBy(baseType)
    // Its abstract editor opener, the gate that calls it when signed in, and the Activity it shows over.
    val opener = base.methods.singleOrNull {
        AccessFlags.ABSTRACT.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.isEmpty()
    } ?: throw PatchException("$baseType has no single abstract label editor opener")
    val gate = base.methods.singleOrNull { m ->
        m.implementation?.instructions?.any {
            (it.ref() as? MethodReference)?.let { r -> r.definingClass == baseType && r.name == opener.name && r.parameterTypes.isEmpty() } == true
        } == true
    } ?: throw PatchException("$baseType has no single gate opening the label editor")
    fun isActivity(type: String): Boolean {
        var t: String? = type
        repeat(12) {
            if (t == "Landroid/app/Activity;") return true
            t = t?.let { classDefByOrNull(it)?.superclass } ?: return false
        }
        return false
    }
    val activity = base.fields.singleOrNull { isActivity(it.type) } ?: throw PatchException("$baseType holds no single Activity")

    // Where an editor opener reads a register from: a field chain starting at this (empty: this itself).
    fun chain(code: List<Instruction>, before: Int, register: Int, self: Int): List<FieldReference> {
        for (i in before - 1 downTo 0) {
            val insn = code[i]
            if (!insn.opcode.setsRegister() || (insn as? OneRegisterInstruction)?.registerA != register) continue
            if (insn.opcode == Opcode.CHECK_CAST) continue
            if (insn.opcode != Opcode.IGET_OBJECT && insn.opcode != Opcode.IGET_BOOLEAN) {
                throw PatchException("label editor argument v$register comes from ${insn.opcode}")
            }
            return chain(code, i, (insn as TwoRegisterInstruction).registerB, self) + (insn.ref() as FieldReference)
        }
        if (register != self) throw PatchException("label editor argument v$register is not read from a field")
        return emptyList()
    }
    fun load(fields: List<FieldReference>) = fields.mapIndexed { i, f ->
        "${if (f.type == "Z") "iget-boolean" else "iget-object"} v0, ${if (i == 0) "p0" else "v0"}, ${f.definingClass}->${f.name}:${f.type}"
    }.joinToString("\n")

    for (type in prompts) {
        val prompt = mutableClassDefBy(type)
        val open = prompt.methods.singleOrNull { it.name == opener.name && it.parameterTypes.isEmpty() && it.returnType == "V" }
            ?: throw PatchException("$type does not open the label editor")
        val code = open.implementation!!.instructions.toList()
        val at = code.indices.filter { code[it].opcode == Opcode.INVOKE_STATIC }.singleOrNull()
            ?: throw PatchException("$type opens the label editor other than by one static call")
        val editor = code[at] as Instruction35c
        val params = (editor.reference as MethodReference).parameterTypes.map(CharSequence::toString)
        if (params.size != 3 || params[2] != "Z") throw PatchException("$type's label editor takes $params")
        if (code.subList(0, at + 1).any { it.location.labels.isNotEmpty() }) throw PatchException("$type's label editor is reached by a branch")
        val refType = params[1]
        // The place reference's null-safe getter: static, takes the reference, returns its Serializable.
        val unwrap = classDefByOrNull(refType)?.methods?.singleOrNull { m ->
            AccessFlags.STATIC.isSet(m.accessFlags) && m.parameterTypes.map { it.toString() } == listOf(refType) &&
                m.returnType == "Ljava/io/Serializable;"
        } ?: throw PatchException("$refType has no single static getter")
        val self = open.implementation!!.registerCount - 1
        val place = chain(code, at, editor.registerD, self)
        val contact = chain(code, at, editor.registerE, self)
        if (place.lastOrNull()?.type != refType || contact.lastOrNull()?.type != "Z") {
            throw PatchException("$type's label editor arguments are not fields: $place, $contact")
        }
        prompt.methods.add(
            ImmutableMethod(
                type, "uaLabel", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "Z",
                AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(6),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        ${load(contact)}
                        if-nez v0, :maps
                        ${load(place)}
                        invoke-static { v0 }, $refType->${unwrap.name}($refType)Ljava/io/Serializable;
                        move-result-object v0
                        instance-of v1, v0, $placeType
                        if-eqz v1, :maps
                        check-cast v0, $placeType
                        invoke-virtual { v0 }, $placeType->${nameGetter.name}()Ljava/lang/String;
                        move-result-object v1
                        invoke-virtual { v0 }, $placeType->${featureIdGetter.name}()${featureIdGetter.returnType}
                        move-result-object v2
                        invoke-virtual { v0 }, $placeType->${positionGetter.name}()${positionGetter.returnType}
                        move-result-object v3
                        invoke-static { p1, v1, v2, v3 }, $SAVED_PLACES->label(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Z
                        move-result v0
                        return v0
                        :maps
                        const/4 v0, 0x0
                        return v0
                    """,
                )
            },
        )
    }
    // The base answers no, so a prompt of any other kind keeps Maps' own.
    base.methods.add(
        ImmutableMethod(
            baseType, "uaLabel", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), "Z",
            AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(3),
        ).toMutable().apply {
            addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        },
    )
    if (gate.implementation!!.registerCount - 1 < 1) throw PatchException("label prompt gate has no room for the hook")
    gate.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, $baseType->${activity.name}:${activity.type}
            invoke-virtual { p0, v0 }, $baseType->uaLabel(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :maps_label
            return-void
        """,
        ExternalLabel("maps_label", gate.implementation!!.instructions.first()),
    )
}
