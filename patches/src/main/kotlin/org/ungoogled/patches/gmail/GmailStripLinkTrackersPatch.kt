package org.ungoogled.patches.gmail

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL
import org.ungoogled.patches.gmail.GmailConstants.GMAIL_EXTENSION

/** The WebViewClient every message view's links go through (its subclasses call it with the link), as gmail-link://?url=<link>&safeRedirectUrl=<google.com/url redirect>. */
private const val CONVERSATION_WEB_VIEW_CLIENT = "com/android/mail/browse/cv/AbstractConversationWebViewClient"

@Suppress("unused")
val gmailStripLinkTrackersPatch = bytecodePatch(
    name = "Strip link trackers",
    description = "Opens links without Google's redirect and without utm and click-tracking parameters.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GMAIL)
    extendWith(GMAIL_EXTENSION)

    execute {
        val client = classesLogging(CONVERSATION_WEB_VIEW_CLIENT).singleOrNull()
            ?: throw PatchException("message view's link handler ($CONVERSATION_WEB_VIEW_CLIENT) not found")
        val method = mutableClassDefBy(client.type).methods.singleOrNull {
            it.name == "shouldOverrideUrlLoading" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/webkit/WebView;", "Ljava/lang/String;")
        } ?: throw PatchException("message view's link handler no longer has shouldOverrideUrlLoading(WebView, String)")
        // p2 is the link; it is cleaned before anything reads it. /range: the method's 26 registers put p2 past v15.
        method.addInstructions(
            0,
            """
                invoke-static/range { p2 .. p2 }, Lorg/ungoogled/gmail/Links;->clean(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p2
            """,
        )
    }
}
