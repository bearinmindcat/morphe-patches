package org.ungoogled.samsungvoicerecorder;

import android.media.MediaPlayer;
import android.os.Process;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.storage.StorageVolume;

import java.io.File;

/**
 * One UI members Voice Recorder needs a real answer for off One UI, from Android's own calls. Everything else a phone
 * lacks answers "absent" (see OneUi).
 */
final class Fallbacks {
    static final Object NONE = new Object();

    private Fallbacks() {
    }

    static Object call(Object receiver, String key, Object[] args) {
        switch (key) {
            case "Landroid/media/MediaPlayer;->semSeekTo(II)V": {
                // Playback seeks (the timeline, skip back and forward) go through One UI's seek.
                int mode = (Integer) args[1];
                ((MediaPlayer) receiver).seekTo((long) (Integer) args[0],
                        mode >= MediaPlayer.SEEK_PREVIOUS_SYNC && mode <= MediaPlayer.SEEK_CLOSEST ? mode : MediaPlayer.SEEK_CLOSEST);
                return null;
            }
            case "Landroid/media/MediaRecorder;->semSetDurationInterval(I)V":
                RecorderProgress.interval((android.media.MediaRecorder) receiver, (Integer) args[0]);
                return null;
            case "Landroid/os/storage/StorageVolume;->semGetPath()Ljava/lang/String;": {
                File directory = ((StorageVolume) receiver).getDirectory();
                return directory == null ? null : directory.getPath();
            }
            case "Landroid/os/storage/StorageVolume;->semGetSubSystem()Ljava/lang/String;":
                return ((StorageVolume) receiver).isRemovable() ? "sd" : "fuse";
            case "Landroid/os/VibrationEffect;->semCreateWaveform(IILandroid/os/VibrationEffect$SemMagnitudeType;)Landroid/os/VibrationEffect;":
                return VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            case "Landroid/os/UserHandle;->semGetMyUserId()I":
            case "Landroid/os/UserHandle;->semGetCallingUserId()I":
            case "Landroid/app/ActivityManager;->semGetCurrentUser()I":
                return Process.myUid() / 100000;
        }
        return NONE;
    }

    static Object get(String key) {
        if ("Landroid/os/UserHandle;->SEM_OWNER:Landroid/os/UserHandle;".equals(key)) return UserHandle.getUserHandleForUid(0);
        return NONE;
    }
}
