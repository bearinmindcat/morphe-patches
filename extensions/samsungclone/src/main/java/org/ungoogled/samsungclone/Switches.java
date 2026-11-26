package org.ungoogled.samsungclone;

/**
 * A switch over strings (Java's, Kotlin's when) compares String.hashCode() with the hash codes of its case strings,
 * worked out when the app was built. Where the patch renamed a case string (an action, the package, a sibling's name),
 * the renamed string has another hash and would skip its case: the patch generates hash(String) here, which answers a
 * renamed string's original hash code (and any other string's own), and sends those switches' hashCode calls to it.
 */
public final class Switches {
    private Switches() {
    }
}
