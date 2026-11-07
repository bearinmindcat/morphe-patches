package org.ungoogled.asuslauncher;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Java library calls newer than Android 13's runtime (Stream.toList is Android 14's, the rest Android 15's Java 21),
 * which the launcher makes without checking: the patch sends them here, and each does the same with calls every Android
 * has (on every phone, so it behaves the same everywhere).
 */
public final class Java21 {
    private Java21() {
    }

    public static List<?> toList(Stream<?> stream) {
        return Collections.unmodifiableList(new ArrayList<>(stream.collect(Collectors.toList())));
    }

    public static Object getFirst(List<?> list) {
        if (list.isEmpty()) throw new NoSuchElementException();
        return list.get(0);
    }

    public static Object getLast(List<?> list) {
        if (list.isEmpty()) throw new NoSuchElementException();
        return list.get(list.size() - 1);
    }

    public static Object removeLast(List<?> list) {
        if (list.isEmpty()) throw new NoSuchElementException();
        return list.remove(list.size() - 1);
    }

    @SuppressWarnings("unchecked")
    public static void addFirst(List<?> list, Object element) {
        ((List<Object>) list).add(0, element);
    }

    @SuppressWarnings("unchecked")
    public static void addLast(List<?> list, Object element) {
        ((List<Object>) list).add(element);
    }

    /** A LinkedHashMap's eldest entry, removed (a snapshot, as Java 21's). */
    public static Map.Entry<?, ?> pollFirstEntry(Map<?, ?> map) {
        Iterator<? extends Map.Entry<?, ?>> it = map.entrySet().iterator();
        if (!it.hasNext()) return null;
        Map.Entry<?, ?> e = it.next();
        Map.Entry<?, ?> copy = new AbstractMap.SimpleImmutableEntry<>(e.getKey(), e.getValue());
        it.remove();
        return copy;
    }

    public static boolean isEmpty(CharSequence s) {
        return s.length() == 0;
    }

    public static int ceilDiv(int x, int y) {
        int q = x / y;
        // Integer division rounds toward zero: one more where it rounded down (same signs, a remainder).
        if ((x ^ y) >= 0 && q * y != x) q++;
        return q;
    }

    public static float clamp(float value, float min, float max) {
        if (!(min <= max)) throw new IllegalArgumentException(min + " > " + max);
        return Math.min(max, Math.max(value, min));
    }

    public static int clamp(long value, int min, int max) {
        if (min > max) throw new IllegalArgumentException(min + " > " + max);
        return (int) Math.min(max, Math.max(value, min));
    }

    /** A uniform value in [0, bound) (Java 17's RandomGenerator.nextLong(bound)). */
    public static long nextLong(Random random, long bound) {
        if (bound <= 0) throw new IllegalArgumentException("bound must be positive");
        long m = bound - 1;
        long r = random.nextLong();
        if ((bound & m) == 0L) return r & m;
        for (long u = r >>> 1; u + m - (r = u % bound) < 0L; u = random.nextLong() >>> 1) {
            // Rejects the values that would make the result uneven.
        }
        return r;
    }
}
