package org.ungoogled.patches.gmail

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL

/** The thread list's "running out of storage" banner -- the one that can be dismissed. The one that cannot (storage full) is kept. */
private const val DISMISSIBLE_STORAGE_BANNER = "Lcom/google/android/apps/gmail/libraries/storagealerts/impl/StorageAlertsDismissibleBannerImpl;"
/** The Google AI Pro (Gemini) upsell's banners and promo cards. */
private const val GEMINI_UPSELL = "com/google/android/apps/gmail/featurelibraries/gemnovaupsell/impl/GemnovaUpsellImpl"
/** The account menu's Workspace offers ("Upgrade", "Get a business email"). */
private const val WORKSPACE_OFFERS = "Failed to get recommendation manager"
private const val ACCOUNT = "Lcom/google/android/apps/gmail/libraries/accounts/android/GmailAndroidAccount;"

@Suppress("unused")
val gmailHideUpsellsPatch = bytecodePatch(
    name = "Hide upsells",
    description = "Hides storage nags and Google One, Gemini and Workspace upgrade offers.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GMAIL)

    execute {
        // 1. The dismissible storage banner ("You're running out of storage", "Get storage"): its bind now sees
        //    no banner, which is how Gmail itself hides it. The banner Gmail cannot dismiss -- the account is
        //    full and mail stops -- is a different view and stays.
        mutableClassDefBy(DISMISSIBLE_STORAGE_BANNER).methods.filter {
            it.returnType == "V" && it.parameterTypes.size == 2 && !AccessFlags.STATIC.isSet(it.accessFlags) && it.name != "<init>"
        }.let { binds ->
            val bind = binds.singleOrNull() ?: throw PatchException("storage banner: expected one bind, found ${binds.size}")
            val state = bind.parameterTypes[0].toString()
            val instructions = bind.implementation!!.instructions.toList()
            // (state, view model): this, p1, p2 are the last three registers; p1 holds the state.
            val parameter = bind.implementation!!.registerCount - 2
            val read = instructions.indexOfFirst { insn ->
                insn.opcode == Opcode.IGET_OBJECT && (insn as TwoRegisterInstruction).registerB == parameter &&
                    ((insn as ReferenceInstruction).reference as FieldReference).definingClass == state
            }
            if (read < 0) throw PatchException("storage banner bind no longer reads its banner first")
            val register = (instructions[read] as TwoRegisterInstruction).registerA
            if (register > 15) throw PatchException("storage banner register v$register out of const/4 range")
            bind.addInstruction(read + 1, "const/4 v$register, 0x0")
        }

        // 2. The account menu's storage card ("x GB of 15 GB used", "Get storage"): its model is never built,
        //    which Gmail already does while it has nothing to show.
        val cardTitle = resourceId(ResourceType.STRING, "og_bento_storage_card_title").toInt()
        val cards = mutableListOf<Pair<String, String>>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                if (m.parameterTypes.isNotEmpty() || !m.returnType.startsWith("L")) continue
                if (m.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == cardTitle } == true) cards += c.type to m.name
            }
        }
        val (cardClass, cardMethod) = cards.singleOrNull() ?: throw PatchException("account menu storage card: expected one model builder, found ${cards.size}")
        mutableClassDefBy(cardClass).methods.single { it.name == cardMethod && it.parameterTypes.isEmpty() }.apply {
            if (implementation!!.registerCount < 2) throw PatchException("storage card builder has no free register")
            addInstructions(0, "const/4 v0, 0x0\nreturn-object v0")
        }

        val anchors = methodsUsing(setOf(WORKSPACE_OFFERS))

        // 3. Google AI Pro (Gemini) offers: the upsell's card composable and its search promo banner draw nothing.
        val upsell = classesLogging(GEMINI_UPSELL).singleOrNull() ?: throw PatchException("Gemini upsell not found")
        val banner = upsell.methods.filter { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/ViewStub;", ACCOUNT) }
        val card = upsell.methods.filter { it.returnType == "V" && it.parameterTypes.size == 5 && it.parameterTypes[0].toString() == ACCOUNT }
        if (banner.size != 1 || card.size != 1) throw PatchException("Gemini upsell: expected one banner and one card, found ${banner.size} and ${card.size}")
        mutableClassDefBy(upsell.type).methods.filter { it.name == banner.single().name || it.name == card.single().name }
            .filter { it.parameterTypes == banner.single().parameterTypes || it.parameterTypes == card.single().parameterTypes }
            .also { if (it.size != 2) throw PatchException("Gemini upsell methods changed") }
            .forEach { it.addInstructions(0, "return-void") }

        // 4. The account menu's Workspace offers: the action list is built empty, as when Gmail cannot reach
        //    its recommendations.
        // The action builder (account, continuation); the line is also in a shared lambda class, which is not it.
        val offers = anchors[WORKSPACE_OFFERS].orEmpty().filter {
            it.method.parameterTypes.size == 2 && it.method.parameterTypes[0].toString() == "Landroid/accounts/Account;"
        }.singleOrNull() ?: throw PatchException("Workspace offers: expected one action builder taking an account")
        val code = offers.method.implementation!!.instructions.toList()
        val failure = code.indexOfFirst { it.stringOrNull() == WORKSPACE_OFFERS }
        val list = code.drop(failure).firstNotNullOfOrNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                insn.opcode == Opcode.INVOKE_DIRECT && it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;")
            }?.definingClass
        } ?: throw PatchException("Workspace offers no longer fall back to an empty list")
        if (code.drop(failure).none { (it as? ReferenceInstruction)?.reference.let { r -> r is TypeReference && r.type == list } }) {
            throw PatchException("Workspace offers' empty list is no longer created after the failure")
        }
        mutable(offers).apply {
            if (implementation!!.registerCount < 4) throw PatchException("Workspace offers have no free registers")
            addInstructions(
                0,
                """
                    new-instance v0, $list
                    sget-object v1, Ljava/util/Collections;->EMPTY_LIST:Ljava/util/List;
                    invoke-direct { v0, v1 }, $list-><init>(Ljava/util/List;)V
                    return-object v0
                """,
            )
        }
    }
}
