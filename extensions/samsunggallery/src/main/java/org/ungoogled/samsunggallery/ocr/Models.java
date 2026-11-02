package org.ungoogled.samsunggallery.ocr;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The text recognizer's model files: PaddleOCR's PP-OCRv5 mobile networks as converted for ncnn by the ncnn project
 * (the files its own PP-OCRv5 example loads) and PaddleOCR's character list, from their GitHub repositories at fixed
 * commits. Downloaded once, after the user allowed it, into the app's private storage; every file must match its
 * SHA-256 or it is thrown away. Nothing else is ever downloaded, and no code: the runtime ships in the app.
 */
final class Models {
    private static final String TAG = "UA";
    private static final String NCNN_ASSETS =
            "https://raw.githubusercontent.com/nihui/ncnn-assets/9489f876aebdaa846af3786f70ea5da6be198243/models/";
    private static final String PADDLE_OCR =
            "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/a38c087bcb2579f9ccc2068aea02ec893b1c2311/ppocr/utils/dict/";

    static final String DET_PARAM = "PP_OCRv5_mobile_det.ncnn.param";
    static final String DET_MODEL = "PP_OCRv5_mobile_det.ncnn.bin";
    static final String REC_PARAM = "PP_OCRv5_mobile_rec.ncnn.param";
    static final String REC_MODEL = "PP_OCRv5_mobile_rec.ncnn.bin";
    static final String DICTIONARY = "ppocrv5_dict.txt";

    private static final String[][] FILES = {
            {DET_PARAM, NCNN_ASSETS, "358f459680ae0e7a73e477469e529ce116f68c629019ec7a0b6457d2d9117934", "24821"},
            {DET_MODEL, NCNN_ASSETS, "857a96bc963725105b78a178dfcc3c0c3db1a7b9eef32244367b2cb105ccf60b", "2357216"},
            {REC_PARAM, NCNN_ASSETS, "f52a6586ac3338d8c350db0c9f3c55ff2ecd3763327f9bc7f0efc091f8a63e74", "20031"},
            {REC_MODEL, NCNN_ASSETS, "49d9907a55ba20fa6637f9f788f66ab00793bc8ce57a733a09dbe86b9a2e3db0", "8242276"},
            {DICTIONARY, PADDLE_OCR, "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b", "74012"},
    };

    /** All files together, for the consent dialog. */
    static final int MEGABYTES = 11;

    private Models() {
    }

    static File dir(Context context) {
        return new File(context.getFilesDir(), "extract_text");
    }

    static File file(Context context, String name) {
        return new File(dir(context), name);
    }

    /** Whether every file is there (each was checked against its hash when it was saved). */
    static boolean ready(Context context) {
        for (String[] f : FILES) {
            File file = file(context, f[0]);
            if (!file.isFile() || file.length() != Long.parseLong(f[3])) return false;
        }
        return true;
    }

    /** Downloads the missing files; true when all are there and verified. Blocks: never on the main thread. */
    static boolean download(Context context) {
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        for (String[] f : FILES) {
            File target = file(context, f[0]);
            if (target.isFile() && target.length() == Long.parseLong(f[3])) continue;
            File part = new File(dir, f[0] + ".part");
            try {
                String hash = fetch(new URL(f[1] + f[0]), part);
                if (!hash.equals(f[2])) {
                    Log.w(TAG, "extract text: " + f[0] + " does not match its SHA-256, discarded");
                    part.delete();
                    return false;
                }
                if (!part.renameTo(target)) return false;
            } catch (IOException e) {
                Log.w(TAG, "extract text: download of " + f[0] + " failed: " + e);
                part.delete();
                return false;
            }
        }
        return ready(context);
    }

    private static String fetch(URL url, File to) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    sha256.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha256.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            connection.disconnect();
        }
    }
}
