package org.ungoogled.patches.shared

import app.morphe.patcher.patch.BytecodePatchContext
import org.ungoogled.patches.samsung.redirectCalls

/**
 * Java library calls of Android 14 and 15 (Stream.toList, Java 21's List.getFirst / getLast / addFirst ..., Math.clamp,
 * ...) that a launcher built for newer Android makes without checking: Android 13's runtime has none of them (a call is
 * a NoSuchMethodError). Each call goes to the same-named static method of the extension's Java21 class (receiver
 * first), which does the same with calls every Android has.
 */
private val NEWER_JAVA_CALLS = listOf(
    "Ljava/util/stream/Stream;->toList()Ljava/util/List;" to "toList(Ljava/util/stream/Stream;)Ljava/util/List;",
    "Ljava/util/List;->getFirst()Ljava/lang/Object;" to "getFirst(Ljava/util/List;)Ljava/lang/Object;",
    "Ljava/util/ArrayList;->getFirst()Ljava/lang/Object;" to "getFirst(Ljava/util/List;)Ljava/lang/Object;",
    "Ljava/util/concurrent/CopyOnWriteArrayList;->getFirst()Ljava/lang/Object;" to "getFirst(Ljava/util/List;)Ljava/lang/Object;",
    "Ljava/util/List;->getLast()Ljava/lang/Object;" to "getLast(Ljava/util/List;)Ljava/lang/Object;",
    "Ljava/util/ArrayList;->getLast()Ljava/lang/Object;" to "getLast(Ljava/util/List;)Ljava/lang/Object;",
    "Ljava/util/ArrayList;->removeLast()Ljava/lang/Object;" to "removeLast(Ljava/util/List;)Ljava/lang/Object;",
    "Ljava/util/List;->addFirst(Ljava/lang/Object;)V" to "addFirst(Ljava/util/List;Ljava/lang/Object;)V",
    "Ljava/util/ArrayList;->addFirst(Ljava/lang/Object;)V" to "addFirst(Ljava/util/List;Ljava/lang/Object;)V",
    "Ljava/util/List;->addLast(Ljava/lang/Object;)V" to "addLast(Ljava/util/List;Ljava/lang/Object;)V",
    "Ljava/util/LinkedHashMap;->pollFirstEntry()Ljava/util/Map\$Entry;" to "pollFirstEntry(Ljava/util/Map;)Ljava/util/Map\$Entry;",
    "Ljava/lang/CharSequence;->isEmpty()Z" to "isEmpty(Ljava/lang/CharSequence;)Z",
    "Ljava/lang/StringBuilder;->isEmpty()Z" to "isEmpty(Ljava/lang/CharSequence;)Z",
    "Ljava/lang/Math;->ceilDiv(II)I" to "ceilDiv(II)I",
    "Ljava/lang/Math;->clamp(FFF)F" to "clamp(FFF)F",
    "Ljava/lang/Math;->clamp(JII)I" to "clamp(JII)I",
    "Ljava/security/SecureRandom;->nextLong(J)J" to "nextLong(Ljava/util/Random;J)J",
)

/** Sends the app's NEWER_JAVA_CALLS to [helper] (the extension's Java21 class). Returns how many calls moved. */
internal fun BytecodePatchContext.redirectNewerJavaCalls(helper: String): Int =
    redirectCalls(NEWER_JAVA_CALLS.associate { (call, shape) -> call to "$helper->$shape" })
