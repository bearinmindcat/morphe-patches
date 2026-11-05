package org.ungoogled.samsunggallery.search;

import android.content.Context;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The words a photo can get (assets/search_by_content/labels.bin, made by native/search/convert.py): about two
 * thousand everyday English words and phrases with their text embeddings from nomic-embed-text-v1.5 (computed once,
 * off the phone: there is no text model here), each word's usual similarity over calibration photos, and Gallery's
 * own scene keys with the words that put a photo in them. The rules are tuned on test photos (convert.py).
 */
final class SearchLabels {
    /** Gallery files every scenery key under "Scenery" and every document key under "Documents" too (as One UI does). */
    static final String SCENERY = "Scenery";
    static final String DOCUMENTS = "documents";

    final int version;
    private final String[] words;
    private final float[] scale;
    private final float[] mean;
    private final float[] deviation;
    private final byte[] vectors;
    private final int dim;
    private final Key[] keys;
    /** For a word: the key a photo must have to keep it (-1: none). */
    private final int[] requires;
    private final float zWord, deltaWord, floor, zHead;
    private final int maxWords, keyRank, keyPair;

    private static final class Key {
        String name;
        String parent;
        int[] members;
        int[] heads;
    }

    /** A photo's tags: words (search only) and Gallery keys with their parent (null = a Things category). */
    static final class Tags {
        final List<String> words = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        final List<String> parents = new ArrayList<>();
        final List<Float> scores = new ArrayList<>();
    }

    private static volatile SearchLabels loaded;

    static SearchLabels get(Context context) throws IOException {
        SearchLabels l = loaded;
        if (l == null) {
            synchronized (SearchLabels.class) {
                if (loaded == null) loaded = new SearchLabels(SearchModel.asset(context, "labels.bin"));
                l = loaded;
            }
        }
        return l;
    }

    private SearchLabels(byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != 0x424c4755 /* "UGLB" */) throw new IOException("not a labels file");
        version = b.getInt();
        int n = b.getInt();
        dim = b.getInt();
        words = new String[n];
        for (int i = 0; i < n; i++) words[i] = string(b);
        scale = floats(b, n);
        mean = floats(b, n);
        deviation = floats(b, n);
        vectors = new byte[n * dim];
        b.get(vectors);
        keys = new Key[b.getInt()];
        for (int k = 0; k < keys.length; k++) {
            Key key = new Key();
            key.name = string(b);
            key.parent = string(b);
            if (key.parent.isEmpty()) key.parent = null;
            key.members = shorts(b);
            key.heads = shorts(b);
            keys[k] = key;
        }
        requires = new int[n];
        Arrays.fill(requires, -1);
        for (int r = b.getInt(); r > 0; r--) {
            int word = b.getShort() & 0xffff;
            requires[word] = b.getShort() & 0xffff;
        }
        float[] p = floats(b, b.getInt());
        zWord = p[0];
        deltaWord = p[1];
        floor = p[2];
        maxWords = (int) p[3];
        keyRank = (int) p[4];
        keyPair = (int) p[5];
        zHead = p[6];
    }

    private static String string(ByteBuffer b) {
        byte[] s = new byte[b.getShort() & 0xffff];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    private static float[] floats(ByteBuffer b, int n) {
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = b.getFloat();
        return f;
    }

    private static int[] shorts(ByteBuffer b) {
        int[] s = new int[b.getShort() & 0xffff];
        for (int i = 0; i < s.length; i++) s[i] = b.getShort() & 0xffff;
        return s;
    }

    /**
     * The words that fit a photo best: close to its best word, and well above how much each word usually fits
     * (some words fit everything a little). A Gallery key when one of its words is among the photo's best two, or
     * two of its words are among the best six (for dogs, cats and animals one of a few plain words must fit too).
     * Phrases that put a thing in a scene ("dog in the snow") stay only with the thing's key.
     */
    Tags choose(float[] embedding) {
        int n = words.length;
        float[] s = new float[n];
        float[] z = new float[n];
        float top = -1;
        for (int w = 0; w < n; w++) {
            float sum = 0;
            int base = w * dim;
            for (int i = 0; i < dim; i++) sum += vectors[base + i] * embedding[i];
            s[w] = sum * scale[w];
            z[w] = (s[w] - mean[w]) / deviation[w];
            if (s[w] > top) top = s[w];
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, c) -> Float.compare(s[c], s[a]));
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < n && chosen.size() < maxWords; i++) {
            int w = order[i];
            if (s[w] < top - deltaWord || s[w] < floor) break;
            if (z[w] >= zWord) chosen.add(w);
        }
        Tags tags = new Tags();
        boolean[] has = new boolean[keys.length];
        for (int k = 0; k < keys.length; k++) {
            Key key = keys[k];
            if (key.heads.length > 0) {
                float best = Float.NEGATIVE_INFINITY;
                for (int h : key.heads) best = Math.max(best, z[h]);
                if (best < zHead) continue;
            }
            int first = -1, near = 0;
            for (int r = 0; r < chosen.size(); r++) {
                if (contains(key.members, chosen.get(r))) {
                    if (first < 0) first = r;
                    if (r < keyPair) near++;
                }
            }
            if (first >= 0 && (first < keyRank || near >= 2)) {
                has[k] = true;
                tags.keys.add(key.name);
                tags.parents.add(key.parent);
                tags.scores.add(s[chosen.get(first)]);
            }
        }
        for (int w : chosen) {
            if (requires[w] < 0 || has[requires[w]]) tags.words.add(words[w]);
        }
        return tags;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }
}
