package org.ungoogled.patches.gmail

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL
import org.ungoogled.patches.gmail.GmailConstants.GMAIL_EXTENSION

private const val LISTENABLE_FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"

/** The trace names of AdItemsCoordinatorImpl's two lookups: ads for a tab (Primary, Promotions, Social) and for a promo chip. */
private val AD_LOOKUPS = setOf(
    "AdItemsCoordinatorImpl.getAdEntriesForViewType",
    "AdItemsCoordinatorImpl.getAdEntriesForPromoFilterChip",
)

/**
 * Gmail's sponsored mails are server items that AdItemsCoordinatorImpl places into the thread list. Its two
 * lookups both read the stored ad entries through one private fetch and then pick the ones for the tab or chip;
 * that fetch now answers "no entries", so both lookups return an empty list. With no ad entries there is no
 * row, no gap and no "ad shown" report.
 */
@Suppress("unused")
val gmailHideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Removes sponsored mail from the inbox, Promotions and Social.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GMAIL)
    extendWith(GMAIL_EXTENSION)

    execute {
        val lookups = methodsUsing(AD_LOOKUPS)
        val found = AD_LOOKUPS.map { lookups.single(it, "ad lookup") }
        val coordinator = found.map { it.type }.distinct().singleOrNull()
            ?: throw PatchException("the two ad lookups are no longer in one class")

        // The fetch both lookups call: a private method of the coordinator taking the trace name.
        val fetches = found.map { lookup ->
            lookup.method.implementation!!.instructions.mapNotNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf {
                    insn.opcode == Opcode.INVOKE_DIRECT && it.definingClass == coordinator &&
                        it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") && it.returnType == LISTENABLE_FUTURE
                }?.name
            }.distinct().singleOrNull() ?: throw PatchException("ad lookup ${lookup.method.name} no longer calls one fetch")
        }.distinct()
        val fetchName = fetches.singleOrNull() ?: throw PatchException("the two ad lookups call different fetches: $fetches")

        mutableClassDefBy(coordinator).methods.single {
            it.name == fetchName && it.returnType == LISTENABLE_FUTURE && it.parameterTypes.size == 1
        }.apply {
            if (!AccessFlags.PRIVATE.isSet(accessFlags)) throw PatchException("the ad fetch is no longer private")
            // v0 is a local: the fetch has this and one parameter at the top of its registers.
            if (implementation!!.registerCount < 3) throw PatchException("the ad fetch has no free register")
            addInstructions(
                0,
                """
                    invoke-static {}, Lorg/ungoogled/gmail/Done;->noAds()$LISTENABLE_FUTURE
                    move-result-object v0
                    return-object v0
                """,
            )
        }
    }
}
