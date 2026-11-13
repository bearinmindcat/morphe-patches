package org.ungoogled.patches.samsungvideolibrary.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

/** The video list's onResume(): shows the end-of-support notice until its OK button is pressed. */
internal object EndOfSupportNoticeFingerprint : Fingerprint(
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string("show_eos_notice")),
)

/** The Galaxy Store update check's "may it ask the server?" step (online, network allowed in China). */
internal object UpdateCheckAllowedFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("canUpdate() true")),
)
