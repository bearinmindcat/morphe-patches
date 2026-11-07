package org.ungoogled.lggallery;

import java.util.Arrays;

/**
 * Android's hidden android.util.LongArray (a growable long[]), which LG Gallery's XMP reader uses and Android refuses an
 * installed app: the same methods, the app's own.
 */
public class LongArray {
    private long[] values = new long[10];
    private int size;

    public LongArray() {
    }

    private void grow(int needed) {
        if (needed > values.length) values = Arrays.copyOf(values, Math.max(needed, values.length * 2));
    }

    public void add(long value) {
        add(size, value);
    }

    public void add(int index, long value) {
        if (index < 0 || index > size) throw new IndexOutOfBoundsException("index " + index + ", size " + size);
        grow(size + 1);
        System.arraycopy(values, index, values, index + 1, size - index);
        values[index] = value;
        size++;
    }

    public long get(int index) {
        if (index < 0 || index >= size) throw new ArrayIndexOutOfBoundsException("index " + index + ", size " + size);
        return values[index];
    }

    public int size() {
        return size;
    }

    public long[] toArray() {
        return Arrays.copyOf(values, size);
    }
}
