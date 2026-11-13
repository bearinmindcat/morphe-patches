package org.ungoogled.patches.xiaominotes.misc

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

/** NoteTracker's start: creates Notes' OneTrack instance (Xiaomi's usage statistics, sent by the app itself). */
private object StatisticsStartFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("OneTrack init skipped on international ROM (compliance)"),
)

/**
 * Notes starts Xiaomi's usage statistics (OneTrack, which off HyperOS uploads by itself) unless it believes it runs on
 * a Global build. The region answers already make it skip that; now it never starts, whatever the answers.
 */
internal val statisticsPatch = bytecodePatch(
    description = "Never starts Xiaomi's usage statistics.",
) {
    execute {
        StatisticsStartFingerprint.method.addInstruction(0, "return-void")
    }
}
