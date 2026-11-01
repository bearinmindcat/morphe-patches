package org.ungoogled.honornotes;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * MagicOS's asset manager (HwAssetManager: a small store for an app's secrets, kept by the system) for Honor Notes, which
 * its stand-in calls: each secret is kept in the app's own preferences, encrypted with an AES key of Android's
 * keystore that never leaves it. Handles are random ids, as MagicOS's.
 */
public final class MagicAssets {
    private static final String KEY = "ungoogled_magic_assets";
    private static final String PREFS = "ungoogled_magic_assets";

    private MagicAssets() {
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Stores a secret; its handle, or null. */
    public static synchronized String insert(Context context, String value) {
        if (context == null || value == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] sealed = cipher.doFinal(value.getBytes("UTF-8"));
            byte[] iv = cipher.getIV();
            byte[] all = new byte[1 + iv.length + sealed.length];
            all[0] = (byte) iv.length;
            System.arraycopy(iv, 0, all, 1, iv.length);
            System.arraycopy(sealed, 0, all, 1 + iv.length, sealed.length);
            String handle = UUID.randomUUID().toString();
            prefs(context).edit().putString(handle, Base64.encodeToString(all, Base64.NO_WRAP)).commit();
            return handle;
        } catch (Exception e) {
            android.util.Log.w("UA", "asset insert: " + e);
            return null;
        }
    }

    /** The secret a handle names, or null. */
    public static synchronized String select(Context context, String handle) {
        if (context == null || handle == null) return null;
        String stored = prefs(context).getString(handle, null);
        if (stored == null) return null;
        try {
            byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            int ivLength = all[0];
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 1, ivLength));
            return new String(cipher.doFinal(all, 1 + ivLength, all.length - 1 - ivLength), "UTF-8");
        } catch (Exception e) {
            android.util.Log.w("UA", "asset select: " + e);
            return null;
        }
    }

    public static synchronized boolean delete(Context context, String handle) {
        if (context == null || handle == null) return false;
        return prefs(context).edit().remove(handle).commit();
    }
}
