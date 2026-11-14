package org.ungoogled.patches.photos

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.gmail.classesLogging
import org.ungoogled.patches.gmail.methodsUsing
import org.ungoogled.patches.gmail.mutable
import org.ungoogled.patches.gmail.single
import org.ungoogled.patches.gmail.stringOrNull
import org.ungoogled.patches.photos.PhotosConstants.COMPATIBILITY_PHOTOS
import org.ungoogled.patches.photos.PhotosConstants.PHOTOS_EXTENSION

/** Ask Photos (Gemini in Photos): the per-account store every entry point asks whether to show it. */
private const val ASK_PHOTOS_AVAILABILITY = "Could not get availability of Ask Photos"
/** Ask Photos' feature settings: ready to query, and the state of its entry point. */
private const val ASK_PHOTOS_SETTINGS = "AskPhotosFeatureSettings(isReadyToQueryAskPhotos="

/** The Create tab's launch actions: the enum's constants, and the generative ones left out. */
private const val PHOTO_REMIX = "LAUNCH_PHOTO_REMIX"

/** The Create tab's built-in tool row: the keys of its tools, and the titles of the generative ones. */
private val TOOL_KEYS = setOf("outfit_try_on", "image_to_video", "poptart")
private val AI_TOOL_TITLES = listOf(
    "photos_editor_bluejay_phototovideo_soba_create_title", // Video remix
    "photos_editor_bluejay_looks_section_title", // Moods
    "photos_auratab_impl_shortcuts_outfit_try_on_title",
    "photos_auratab_impl_shortcuts_poptart_title",
    "photos_auratab_impl_shortcuts_remix_title",
    "photos_auratab_impl_shortcuts_remix_title_with_soba", // Photo remix
    "photos_auratab_impl_shortcuts_photo_to_video_title",
)

/** Photos' own switch that turns Magic Editor off (a debug override over a server flag). */
private const val MAGIC_EDITOR_OFF = "debug.photos.disable_udon"
private const val BOOLEAN_SUPPLIER = "Ljava/util/function/BooleanSupplier;"

@Suppress("unused")
val photosHideAiPatch = bytecodePatch(
    name = "Hide AI",
    description = "Hides Ask Photos, Gemini, Help me edit, Magic Editor, Remix and the other generative tools.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PHOTOS)
    extendWith(PHOTOS_EXTENSION)

    execute {
        val anchors = methodsUsing(setOf(ASK_PHOTOS_AVAILABILITY, ASK_PHOTOS_SETTINGS, MAGIC_EDITOR_OFF) + TOOL_KEYS)

        // 1. Ask Photos: every question the availability store answers (show the entry point, ready to query,
        //    show it when opted out -- per surface: search bar, tab, editor's Help me edit) is "no", as for an
        //    account without Gemini, so no Ask button, Ask tab, Help me edit or Ask prompts appear.
        val getters = anchors[ASK_PHOTOS_AVAILABILITY].orEmpty()
        if (getters.size < 4 || getters.map { it.type }.toSet().size != 1 || getters.any { it.method.returnType != "Z" }) {
            throw PatchException("Ask Photos availability store changed: ${getters.size} getters")
        }
        for (getter in getters) mutable(getter).addInstructions(0, "const/4 v0, 0x0\nreturn v0")

        //    Its feature settings say "not ready" and "INELIGIBLE" (1) whatever was computed.
        val settings = anchors.single(ASK_PHOTOS_SETTINGS, "Ask Photos feature settings").classDef
        if (settings.methods.none { m -> m.implementation?.instructions?.any { it.stringOrNull() == "INELIGIBLE" } == true }) {
            throw PatchException("Ask Photos feature settings no longer name the INELIGIBLE state")
        }
        mutableClassDefBy(settings.type).methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Z", "I", "Ljava/util/List;")
        }?.addInstructions(0, "const/4 p1, 0x0\nconst/4 p2, 0x1")
            ?: throw PatchException("Ask Photos feature settings no longer have a (ready, state, actions) constructor")

        // 2. The Create tab: Photos tells Google's server which tools it can open, and the server sends cards
        //    for those; the generative ones are no longer offered, so the tab has no Remix, video remix, photo to
        //    video, outfit or moods cards, and its built-in tool list (made from the same set) none either.
        val actions = classesLogging(PHOTO_REMIX).singleOrNull()?.type ?: throw PatchException("Create tab launch actions not found")
        val adders = mutableListOf<Pair<String, String>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (!AccessFlags.STATIC.isSet(m.accessFlags) || m.returnType != "V" || m.parameterTypes.size != 2) continue
                if (m.parameterTypes[0].toString() != actions) continue
                val readsNumber = m.implementation?.instructions?.any { insn ->
                    ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == actions && it.returnType == "I" } == true
                } == true
                if (readsNumber) adders += c.type to m.name
            }
        }
        val (adderClass, adderName) = adders.singleOrNull() ?: throw PatchException("Create tab: expected one launch-action adder, found ${adders.size}")
        mutableClassDefBy(adderClass).methods.single { it.name == adderName && it.parameterTypes.firstOrNull()?.toString() == actions }.apply {
            if (implementation!!.registerCount < 3) throw PatchException("launch-action adder has no free register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0 }, Lorg/ungoogled/photos/Tools;->aiAction(Ljava/lang/Object;)Z
                    move-result v0
                    if-eqz v0, :add
                    return-void
                """,
                ExternalLabel("add", implementation!!.instructions.first()),
            )
        }

        //    The older built-in tool row (Remix, Moods, Outfit try-on, Poptart, Photo to video, Video remix next to
        //    Collage, Animation...): the generative ones are dropped from the list it returns.
        val rows = TOOL_KEYS.map { key ->
            anchors[key].orEmpty().filter { it.method.returnType == "Ljava/util/List;" && it.method.parameterTypes.isEmpty() }
                .map { it.type to it.method.name }.toSet()
        }.reduce { a, b -> a intersect b }
        val (rowClass, rowName) = rows.singleOrNull() ?: throw PatchException("Create tab tool row: expected one builder, found ${rows.size}")
        val titles = AI_TOOL_TITLES.joinToString(",") { resourceId(ResourceType.STRING, it).toInt().toString() }
        mutableClassDefBy(rowClass).methods.single { it.name == rowName && it.returnType == "Ljava/util/List;" && it.parameterTypes.isEmpty() }.apply {
            val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
            if (returns.isEmpty()) throw PatchException("Create tab tool row no longer returns its list")
            for (index in returns.reversed()) {
                val list = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
                val scratch = if (list == 0) 1 else 0
                if (list > 15) throw PatchException("Create tab tool row returns from v$list")
                addInstructions(
                    index,
                    """
                        const-string v$scratch, "$titles"
                        invoke-static { v$list, v$scratch }, Lorg/ungoogled/photos/Tools;->withoutAi(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;
                        move-result-object v$list
                    """,
                )
            }
        }

        // 3. Magic Editor (Reimagine, the editor's generative tools): Photos' own "Magic Editor off" switch is
        //    pinned on, which is how Photos turns it off where it is not offered.
        val switch = anchors.single(MAGIC_EDITOR_OFF, "Magic Editor switch")
        mutable(switch).apply {
            val code = implementation!!.instructions.toList()
            val at = code.indexOfFirst { it.stringOrNull() == MAGIC_EDITOR_OFF }
            val call = (at + 1 until code.size).firstOrNull { i ->
                val ref = (code[i] as? ReferenceInstruction)?.reference as? MethodReference
                code[i].opcode == Opcode.INVOKE_VIRTUAL && ref?.parameterTypes?.map(CharSequence::toString) == listOf(BOOLEAN_SUPPLIER)
            }?.takeIf { it - at < 12 } ?: throw PatchException("Magic Editor switch no longer takes its value from a BooleanSupplier")
            val supplier = (code[call] as FiveRegisterInstruction).registerD
            addInstruction(call, "sget-object v$supplier, Lorg/ungoogled/photos/Flags;->ON:$BOOLEAN_SUPPLIER")
        }

        // 4. Promos for Ask Photos, Gemini and the generative tools (tooltips, half sheets, banners).
        skipPromosWhen("AI") { promo ->
            """
                iget-object v1, v0, ${promo.idField}
                iget-object v0, v0, ${promo.surfaceField}
                invoke-static { v1, v0 }, Lorg/ungoogled/photos/Promos;->ai(Ljava/lang/String;Ljava/lang/Object;)Z
                move-result v0
            """
        }
    }
}
