package org.ungoogled.patches.photos

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.ungoogled.patches.photos.PhotosConstants.COMPATIBILITY_PHOTOS
import org.ungoogled.patches.photos.PhotosConstants.PHOTOS_EXTENSION

@Suppress("unused")
val photosHidePromosPatch = bytecodePatch(
    name = "Hide promos",
    description = "Hides Google One storage offers, backup nags, print offers and \"try this\" promos.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_PHOTOS)
    extendWith(PHOTOS_EXTENSION)

    execute {
        // 1. Feature promos by category: paid offers (Google One storage, winback), launches ("try" cards, Create
        //    tab stamps), "turn on" asks for free features (notifications, prints, crowdsourcing), backup nags and
        //    Google's in-app messages are not shown. Privacy and legal notices, broken states (storage full, payment
        //    failed), tips and memories are.
        skipPromosWhen("promo category") { promo ->
            """
                iget-object v0, v0, ${promo.categoryField}
                invoke-static { v0 }, Lorg/ungoogled/photos/Promos;->upsell(Ljava/lang/Object;)Z
                move-result v0
            """
        }

        // 2. The account menu's storage card ("x GB of 15 GB used", "Get storage"): its model is never built,
        //    which Photos already does while it has nothing to show. Settings > Backup's storage stays.
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
            if (implementation!!.registerCount < 1) throw PatchException("storage card builder has no free register")
            addInstructions(0, "const/4 v0, 0x0\nreturn-object v0")
        }
    }
}
