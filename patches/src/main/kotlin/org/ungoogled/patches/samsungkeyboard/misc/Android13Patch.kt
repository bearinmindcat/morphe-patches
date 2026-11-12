package org.ungoogled.patches.samsungkeyboard.misc

import app.morphe.patcher.patch.bytecodePatch
import org.ungoogled.patches.samsung.redirectCalls
import org.ungoogled.patches.shared.redirectNewerJavaCalls

private const val JAVA21 = "Lorg/ungoogled/samsungkeyboard/Java21;"

/** Java 21 list calls the keyboard makes (Kotlin's removeFirst/removeLast) beyond the shared list. */
private val KEYBOARD_JAVA_CALLS = mapOf(
    "Ljava/util/ArrayList;->removeFirst()Ljava/lang/Object;" to "$JAVA21->removeFirst(Ljava/util/List;)Ljava/lang/Object;",
    "Ljava/util/List;->removeFirst()Ljava/lang/Object;" to "$JAVA21->removeFirst(Ljava/util/List;)Ljava/lang/Object;",
    "Ljava/util/List;->removeLast()Ljava/lang/Object;" to "$JAVA21->removeLast(Ljava/util/List;)Ljava/lang/Object;",
    "Ljava/util/concurrent/CopyOnWriteArrayList;->removeFirst()Ljava/lang/Object;" to
        "$JAVA21->removeFirst(Ljava/util/List;)Ljava/lang/Object;",
    "Ljava/util/concurrent/CopyOnWriteArrayList;->removeLast()Ljava/lang/Object;" to
        "$JAVA21->removeLast(Ljava/util/List;)Ljava/lang/Object;",
)

/**
 * Samsung Keyboard 5.9 runs from Android 13, but some of its code calls Java 21's list methods (Android 15) without
 * checking: on Android 13 the recent stickers past 30 and the favourite languages past 5 died with NoSuchMethodError.
 * Each call goes to the extension's Java21, which does the same with calls every Android has.
 */
internal val android13Patch = bytecodePatch(
    description = "Lets the keyboard run on Android 13.",
) {
    extendWith(SAMSUNG_KEYBOARD_EXTENSION)

    execute {
        val shared = redirectNewerJavaCalls(JAVA21)
        val own = redirectCalls(KEYBOARD_JAVA_CALLS)
        // No minimum: a build without these calls needs nothing here.
        java.util.logging.Logger.getLogger("SamsungKeyboard").info("Java 21 calls: ${shared + own}")
    }
}
