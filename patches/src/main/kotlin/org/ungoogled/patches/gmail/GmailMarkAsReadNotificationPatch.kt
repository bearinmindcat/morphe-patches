package org.ungoogled.patches.gmail

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL
import org.ungoogled.patches.gmail.GmailConstants.GMAIL_EXTENSION
import org.ungoogled.patches.shared.addInstructionsAtLabel

/** Builds and posts the new-mail notifications of Exchange, IMAP and POP3 accounts (its logger name). */
private const val NOTIFICATIONS_HANDLER = "com/android/mail/utils/NonGoogleAccountNotificationsHandler"
/** A notification button's conversation, account, folder and notification id (Archive, Delete, Reply...). */
private const val NOTIFICATION_ACTION = "Lcom/android/mail/utils/NonGoogleAccountNotificationActionUtils\$NotificationAction;"
private const val ACCOUNT = "Lcom/google/android/apps/gmail/libraries/accounts/legacy/Account;"
private const val CONVERSATION = "Lcom/android/mail/providers/Conversation;"
private const val FOLDER = "Lcom/android/mail/providers/Folder;"
/** The undo-timeout intent's builder sets the conversation's uri as the intent's data. */
private const val UNDO_TIMEOUT = "com.android.mail.action.notification.UNDO_TIMEOUT"
/** Gmail's mail service marks a conversation read and seen, then rebuilds the notifications, on this action. */
private const val MARK_READ = "com.android.mail.action.MARK_READ_RESEND_NOTIFICATIONS"
private const val MAIL_SERVICE = "Lcom/google/android/apps/gmail/libraries/intent/GmailIntentService;"
private const val MARK_READ_CLASS = "Lorg/ungoogled/gmail/MarkRead;"

/**
 * Google accounts' new-mail notifications already have Mark as read (NotificationFactory); Exchange, IMAP and POP3 ones
 * only had Archive or Delete and Reply. Gmail's mail service still handles the old mark-read action
 * ([MARK_READ]: read + seen in Gmail's provider, then the notifications are rebuilt), it just had no button; the button now
 * sends it (MarkRead in the extension).
 */
@Suppress("unused")
val gmailMarkAsReadNotificationPatch = bytecodePatch(
    name = "Mark as read in notifications",
    description = "Adds Mark as read to new-mail notifications of Exchange, IMAP and POP3 accounts.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GMAIL)
    extendWith(GMAIL_EXTENSION)

    execute {
        // The mail service that answers the action: GmailIntentService, one of whose superclasses handles it.
        val handlers = methodsUsing(setOf(MARK_READ))[MARK_READ].orEmpty().map { it.type }.toSet()
        val chain = generateSequence(MAIL_SERVICE) { classDefByOrNull(it)?.superclass }.toList()
        if (chain.none { it in handlers }) throw PatchException("$MAIL_SERVICE no longer handles $MARK_READ (found in $handlers)")

        // The conversation's uri field: the only Conversation uri the undo-timeout intent's builder reads.
        val undo = methodsUsing(setOf(UNDO_TIMEOUT))[UNDO_TIMEOUT].orEmpty().filter { it.method.returnType == "Landroid/app/PendingIntent;" }
            .singleOrNull() ?: throw PatchException("undo-timeout intent's builder not found")
        val uriField = undo.method.implementation!!.instructions.mapNotNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                insn.opcode == Opcode.IGET_OBJECT && it.definingClass == CONVERSATION && it.type == "Landroid/net/Uri;"
            }
        }.distinct().singleOrNull() ?: throw PatchException("conversation uri field not found in the undo-timeout intent's builder")

        // 1. Every notification button is built with its conversation and notification id: remember them.
        val action = mutableClassDefBy(NOTIFICATION_ACTION)
        val constructor = action.methods.singleOrNull { m ->
            m.name == "<init>" && m.parameterTypes.map(CharSequence::toString).let { p ->
                p.size == 8 && p[1] == ACCOUNT && p[2] == CONVERSATION && p[4] == FOLDER && p.subList(5, 8) == listOf("J", "I", "I")
            }
        } ?: throw PatchException("notification button's constructor (type, account, conversation, message, folder, when, kind, id) not found")
        val accountField = constructor.implementation!!.instructions.mapNotNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                insn.opcode == Opcode.IPUT_OBJECT && it.definingClass == NOTIFICATION_ACTION && it.type == ACCOUNT
            }
        }.singleOrNull() ?: throw PatchException("notification button no longer stores its account")
        // Parameters only (p0..p9 = v0..v9, no locals): at the end p1/p2 have been reused for longs and are free;
        // p3 (conversation), p5 (folder) and p9 (notification id) are untouched.
        if (constructor.implementation!!.registerCount != 10) throw PatchException("notification button's constructor changed shape")
        val returns = constructor.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        if (returns.size != 1) throw PatchException("notification button's constructor: expected one return, found ${returns.size}")
        constructor.addInstructionsAtLabel(
            returns.single(),
            """
                iget-object p1, p0, $NOTIFICATION_ACTION->${accountField.name}:$ACCOUNT
                iget-object p2, p3, $CONVERSATION->${uriField.name}:Landroid/net/Uri;
                invoke-static { p9, p1, p5, p2 }, $MARK_READ_CLASS->remember(ILandroid/os/Parcelable;Landroid/os/Parcelable;Landroid/net/Uri;)V
            """,
        )

        // 2. The handler posts every notification through one (Context, id, Notification) method: a conversation's
        //    gets the button.
        val handler = classesLogging(NOTIFICATIONS_HANDLER).singleOrNull()
            ?: throw PatchException("notification handler ($NOTIFICATIONS_HANDLER) not found")
        val post = mutableClassDefBy(handler.type).methods.singleOrNull {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;", "I", "Landroid/app/Notification;")
        } ?: throw PatchException("notification handler no longer posts through (Context, int, Notification)")
        // Three parameters at the top; the locals below them are free at entry (5-bit invoke: all under v16).
        if (post.implementation!!.registerCount !in 5..16) throw PatchException("notification post method changed shape")
        val icon = resourceId(ResourceType.DRAWABLE, "ic_mark_read_wht_24dp").toInt()
        val label = resourceId(ResourceType.STRING, "bt_action_mark_as_read").toInt()
        post.addInstructions(
            0,
            """
                const v0, $icon
                const v1, $label
                invoke-static { p0, p1, p2, v0, v1 }, $MARK_READ_CLASS->add(Landroid/content/Context;ILandroid/app/Notification;II)V
            """,
        )
    }
}
