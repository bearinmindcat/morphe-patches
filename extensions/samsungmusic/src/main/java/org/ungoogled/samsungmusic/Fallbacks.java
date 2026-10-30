package org.ungoogled.samsungmusic;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.widget.AbsSeekBar;
import android.widget.Toast;

import java.io.File;

/**
 * One UI members Samsung Music needs a real answer for off One UI, from Android's own public calls. Everything else a
 * phone lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        try {
            switch (key) {
                case "Landroid/os/UserHandle;->semGetMyUserId()I":
                    return Process.myUid() / 100000;
                case "Landroid/os/UserHandle;->semGetCallingUserId()I":
                    return Binder.getCallingUid() / 100000;
                case "Landroid/provider/Settings$System;->semGetIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I":
                    return Settings.System.getInt((ContentResolver) args[0], (String) args[1], (Integer) args[2]);
                case "Landroid/os/storage/StorageVolume;->semGetPath()Ljava/lang/String;": {
                    File dir = ((StorageVolume) receiver).getDirectory();
                    return dir == null ? null : dir.getPath();
                }
                case "Landroid/widget/Toast;->semMakeAction(Landroid/content/Context;Ljava/lang/CharSequence;ILjava/lang/CharSequence;Landroid/view/View$OnClickListener;)Landroid/widget/Toast;":
                    // A toast with an action button is One UI's; elsewhere the message alone.
                    return Toast.makeText((Context) args[0], (CharSequence) args[1], (Integer) args[2]);
                case "Landroid/media/AudioManager;->semGetFineVolume(I)I":
                    return FineVolume.get((android.media.AudioManager) receiver, (Integer) args[0]);
                case "Landroid/media/AudioManager;->semSetFineVolume(III)V":
                    FineVolume.set((android.media.AudioManager) receiver, (Integer) args[0], (Integer) args[1], (Integer) args[2]);
                    return null;
                case "Landroid/widget/AbsSeekBar;->semGetThumbBounds()Landroid/graphics/Rect;": {
                    Drawable thumb = ((AbsSeekBar) receiver).getThumb();
                    return thumb == null ? new Rect() : new Rect(thumb.getBounds());
                }
            }
        } catch (SecurityException | ClassCastException | NullPointerException e) {
            return NONE;
        }
        return NONE;
    }

    static Object get(String key) {
        switch (key) {
            case "Landroid/os/UserHandle;->SEM_OWNER:Landroid/os/UserHandle;":
                return UserHandle.getUserHandleForUid(0);
            case "Landroid/os/Build$VERSION;->SEM_INT:I":
                // Music's support library picks its code path by this (SamsungSdk.VERSION); below the One UI release
                // of its own Android version it falls back to reflection on hidden Samsung members, which answers null
                // and crashes. The One UI release that shipped with this Android version keeps it on the calls OneUi
                // answers.
                return android.os.Build.VERSION.SDK_INT * 100 + 1;
        }
        return NONE;
    }
}
