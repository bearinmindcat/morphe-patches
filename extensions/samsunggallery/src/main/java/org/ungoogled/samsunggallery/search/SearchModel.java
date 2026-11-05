package org.ungoogled.samsunggallery.search;

import android.content.Context;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

/**
 * The image model: Nomic's nomic-embed-vision-v1.5 (Apache-2.0), downloaded once after the user allowed it, from
 * Nomic's own repository on Hugging Face at a fixed commit: its 8-bit ONNX file, which must match its SHA-256. ncnn's
 * weight file is then written from it on the phone, following the recipe that ships with the patch (assets, made by
 * native/search/convert.py), and must match its SHA-256 too; the ONNX file is deleted afterwards. Nothing else is
 * downloaded, and no code: the runtime ships in the app.
 */
final class SearchModel {
    private static final String TAG = "UA";
    private static final String URL_ONNX = "https://huggingface.co/nomic-ai/nomic-embed-vision-v1.5/resolve/"
            + "e3a725bce72db07ca4adb1d83da08903f3ee02f8/onnx/model_quantized.onnx";
    private static final String ONNX_SHA256 = "ba9107df6e412828dae8c675096209aa39f6536de8ec8d9a872665b54dc750c3";
    private static final long ONNX_SIZE = 96745606L;
    private static final String BIN_SHA256 = "6f9617c186efddd3a215aee12262522556432cd274917f1833ff770ba70aa5b2";
    private static final long BIN_SIZE = 96847380L;
    private static final String LATENT_SHA256 = "2a4096e10494541d98cc2966d5db252aae77e230ade8c5b81b224b5111ff265d";
    /** For the consent text. */
    static final int MEGABYTES = 97;

    static final String ASSETS = "search_by_content/";
    private static final String ONNX = "model_quantized.onnx";
    static final String PARAM = "vision.param";
    static final String BIN = "vision.bin";
    static final String LATENT = "vision.latent";
    private static final String RECIPE = "vision.recipe";
    private static final String VERIFIED = "verified";

    private static final int OP_INT8_TABLE = 1;
    private static final int OP_F32_FLAG = 2;
    private static final int OP_F32_RAW = 3;
    private static final int OP_ROTATION = 5;

    /** The last attempt got a file that did not match its SHA-256 (not a network problem). */
    private static volatile boolean mismatch;

    private SearchModel() {
    }

    static boolean mismatched() {
        return mismatch;
    }

    static File dir(Context context) {
        return new File(context.getFilesDir(), "search_by_content");
    }

    static File file(Context context, String name) {
        return new File(dir(context), name);
    }

    /** Written and checked: ready to load. */
    static boolean ready(Context context) {
        return file(context, VERIFIED).isFile() && file(context, BIN).length() == BIN_SIZE
                && file(context, LATENT).length() == 3072 && file(context, PARAM).isFile();
    }

    /** Bytes of the ONNX file downloaded so far (a stopped download goes on from there). */
    static long downloaded(Context context) {
        File part = file(context, ONNX + ".part");
        return part.isFile() ? part.length() : file(context, ONNX).isFile() ? ONNX_SIZE : 0;
    }

    static long size() {
        return ONNX_SIZE;
    }

    /** Removes everything (Search by content turned off). */
    static void delete(Context context) {
        File[] files = dir(context).listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    /** Downloads and converts the model; true when it is ready. Blocks: never on the main thread. */
    static boolean prepare(Context context, BooleanSupplier stopped) {
        mismatch = false;
        if (ready(context)) return true;
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File onnx = file(context, ONNX);
        try {
            if (!onnx.isFile()) {
                if (!download(context, onnx, stopped)) return false;
            }
            if (!build(context, onnx)) {
                // The source was checked when it arrived; a mismatch here means a broken file on the phone.
                onnx.delete();
                mismatch = true;
                return false;
            }
            onnx.delete();
            return ready(context);
        } catch (IOException e) {
            Log.w(TAG, "search: model not ready: " + e);
            return false;
        }
    }

    private static boolean download(Context context, File onnx, BooleanSupplier stopped) throws IOException {
        File part = file(context, ONNX + ".part");
        long have = part.isFile() ? part.length() : 0;
        if (have > ONNX_SIZE) {
            part.delete();
            have = 0;
        }
        if (have < ONNX_SIZE) {
            Log.i(TAG, "search: downloading the model from " + have + " bytes");
            HttpURLConnection connection = (HttpURLConnection) new URL(URL_ONNX).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            if (have > 0) connection.setRequestProperty("Range", "bytes=" + have + "-");
            try {
                int code = connection.getResponseCode();
                boolean resumed = code == HttpURLConnection.HTTP_PARTIAL;
                if (code != HttpURLConnection.HTTP_OK && !resumed) throw new IOException("HTTP " + code);
                if (!resumed) have = 0;
                try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part, resumed)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        have += n;
                        if (stopped.getAsBoolean()) {
                            Log.i(TAG, "search: download paused at " + have + " bytes");
                            return false;
                        }
                    }
                }
            } finally {
                connection.disconnect();
            }
        }
        if (part.length() != ONNX_SIZE || !ONNX_SHA256.equals(sha256(part))) {
            Log.w(TAG, "search: the model does not match its SHA-256, discarded");
            part.delete();
            mismatch = true;
            return false;
        }
        Log.i(TAG, "search: model downloaded and checked");
        return part.renameTo(onnx);
    }

    /** ncnn's weight file from the ONNX file, by the recipe (native/search/recipe.py build() is the same). */
    private static boolean build(Context context, File onnx) throws IOException {
        long started = System.currentTimeMillis();
        ByteBuffer recipe = ByteBuffer.wrap(asset(context, RECIPE)).order(ByteOrder.LITTLE_ENDIAN);
        if (recipe.getInt() != 0x43524755 /* "UGRC" */ || recipe.getInt() != 1) throw new IOException("unknown recipe");
        int count = recipe.getInt();
        long latentOffset = recipe.getLong();
        int latentCount = recipe.getInt();
        File bin = file(context, BIN);
        File latent = file(context, LATENT);
        file(context, VERIFIED).delete();
        MessageDigest binDigest = digest();
        try (RandomAccessFile src = new RandomAccessFile(onnx, "r");
             OutputStream out = new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(bin), 1 << 16), binDigest)) {
            for (int e = 0; e < count; e++) {
                int op = recipe.get() & 0xff;
                boolean transpose = recipe.get() != 0;
                int rows = recipe.getInt();
                int cols = recipe.getInt();
                long offset = recipe.getLong();
                float scale = recipe.getFloat();
                int n = rows * cols;
                switch (op) {
                    case OP_INT8_TABLE: {
                        byte[] q = read(src, offset, n);
                        if (transpose) q = transpose(q, rows, cols, 1);
                        ByteBuffer head = ByteBuffer.allocate(4 + 256 * 4).order(ByteOrder.LITTLE_ENDIAN);
                        head.putInt(1);
                        for (int i = 0; i < 256; i++) head.putFloat((float) (i - 128) * scale);
                        out.write(head.array());
                        for (int i = 0; i < n; i++) q[i] ^= (byte) 0x80;
                        out.write(q);
                        out.write(new byte[(4 - n % 4) % 4]);
                        break;
                    }
                    case OP_F32_FLAG:
                    case OP_F32_RAW: {
                        byte[] a = read(src, offset, 4 * n);
                        if (transpose) a = transpose(a, rows, cols, 4);
                        if (op == OP_F32_FLAG) out.write(new byte[4]);
                        out.write(a);
                        break;
                    }
                    case OP_ROTATION: {
                        int side = (int) Math.round(Math.sqrt(n));
                        ByteBuffer r = ByteBuffer.allocate(4 * n).order(ByteOrder.LITTLE_ENDIAN);
                        for (int i = 0; i < side; i++) {
                            for (int j = 0; j < side; j++) {
                                float v = 0f;
                                if (i % 2 == 1 && j == i - 1) v = -1f;
                                if (i % 2 == 0 && j == i + 1) v = 1f;
                                r.putFloat(v);
                            }
                        }
                        out.write(r.array());
                        break;
                    }
                    default:
                        throw new IOException("recipe op " + op);
                }
            }
            byte[] lat;
            lat = read(src, latentOffset, 4 * latentCount);
            try (OutputStream l = new FileOutputStream(latent)) {
                l.write(lat);
            }
            if (!LATENT_SHA256.equals(hex(digest().digest(lat)))) {
                Log.w(TAG, "search: the pooling query does not match its SHA-256");
                return false;
            }
        }
        if (bin.length() != BIN_SIZE || !BIN_SHA256.equals(hex(binDigest.digest()))) {
            Log.w(TAG, "search: the converted model does not match its SHA-256");
            bin.delete();
            return false;
        }
        try (OutputStream p = new FileOutputStream(file(context, PARAM))) {
            p.write(asset(context, PARAM));
        }
        new FileOutputStream(file(context, VERIFIED)).close();
        Log.i(TAG, "search: model converted in " + (System.currentTimeMillis() - started) + " ms");
        return true;
    }

    static float[] latent(Context context) throws IOException {
        byte[] b = new byte[3072];
        try (RandomAccessFile f = new RandomAccessFile(file(context, LATENT), "r")) {
            f.readFully(b);
        }
        float[] v = new float[768];
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
        return v;
    }

    static byte[] asset(Context context, String name) throws IOException {
        try (InputStream in = context.getAssets().open(ASSETS + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static byte[] read(RandomAccessFile f, long offset, int length) throws IOException {
        byte[] b = new byte[length];
        f.seek(offset);
        f.readFully(b);
        return b;
    }

    /** [rows][cols] elements of the given size -> [cols][rows]. */
    private static byte[] transpose(byte[] a, int rows, int cols, int size) {
        byte[] t = new byte[a.length];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                System.arraycopy(a, (r * cols + c) * size, t, (c * rows + r) * size, size);
            }
        }
        return t;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest d = digest();
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) d.update(buffer, 0, n);
        }
        return hex(d.digest());
    }

    private static String hex(byte[] digest) {
        StringBuilder s = new StringBuilder();
        for (byte b : digest) s.append(String.format("%02x", b));
        return s.toString();
    }
}
