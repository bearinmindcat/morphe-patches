package android.window;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class TransitionInfo {
    public TransitionInfo(int p0, int p1) {
    }
    public TransitionInfo() {
    }
    public static android.os.Parcelable.Creator CREATOR;
    public void addChange(android.window.TransitionInfo.Change p0) {
    }
    public void addRoot(android.window.TransitionInfo.Root p0) {
    }
    public int findRootIndex(int p0) {
        return 0;
    }
    public android.window.TransitionInfo.AnimationOptions getAnimationOptions() {
        return null;
    }
    public java.util.List getChanges() {
        return new java.util.ArrayList<>();
    }
    public int getDebugId() {
        return 0;
    }
    public int getFlags() {
        return 0;
    }
    public android.window.TransitionInfo.Root getRoot(int p0) {
        return null;
    }
    public int getRootCount() {
        return 0;
    }
    public android.view.SurfaceControl getRootLeash() {
        return null;
    }
    public int getTrack() {
        return 0;
    }
    public int getType() {
        return 0;
    }
    public boolean hasChangesOrSideEffects() {
        return false;
    }
    public static boolean isIndependent(android.window.TransitionInfo.Change p0, android.window.TransitionInfo p1) {
        return false;
    }
    public boolean isKeyguardGoingAway() {
        return false;
    }
    public android.window.TransitionInfo localRemoteCopy() {
        return this;
    }
    public static java.lang.String modeToString(int p0) {
        return "";
    }
    public void releaseAllSurfaces() {
    }
    public void releaseAnimSurfaces() {
    }
    public void setAnimationOptions(android.window.TransitionInfo.AnimationOptions p0) {
    }
    public void setDebugId(int p0) {
    }
    public void setTrack(int p0) {
    }
    public void setUnreleasedWarningCallSiteForAllSurfaces(java.lang.String p0) {
    }
    public static class AnimationOptions {
        public int getAnimations() {
            return 0;
        }
        public int getBackgroundColor() {
            return 0;
        }
        public android.window.TransitionInfo.AnimationOptions.CustomActivityTransition getCustomActivityTransition(boolean p0) {
            return null;
        }
        public int getEnterResId() {
            return 0;
        }
        public int getExitResId() {
            return 0;
        }
        public boolean getOverrideTaskTransition() {
            return false;
        }
        public java.lang.String getPackageName() {
            return "";
        }
        public android.hardware.HardwareBuffer getThumbnail() {
            return null;
        }
        public android.graphics.Rect getTransitionBounds() {
            return new android.graphics.Rect();
        }
        public int getType() {
            return 0;
        }
        public static class CustomActivityTransition {
            public int getCustomBackgroundColor() {
                return 0;
            }
            public int getCustomEnterResId() {
                return 0;
            }
            public int getCustomExitResId() {
                return 0;
            }
        }
    }
    public static class Change {
        public android.content.ComponentName getActivityComponent() {
            return null;
        }
        public android.window.TransitionInfo.AnimationOptions getAnimationOptions() {
            return null;
        }
        public int getBackgroundColor() {
            return 0;
        }
        public android.graphics.Rect getEndAbsBounds() {
            return new android.graphics.Rect();
        }
        public int getEndDisplayId() {
            return 0;
        }
        public int getEndFixedRotation() {
            return 0;
        }
        public android.graphics.Point getEndRelOffset() {
            return new android.graphics.Point();
        }
        public int getEndRotation() {
            return 0;
        }
        public int getFlags() {
            return 0;
        }
        public android.view.SurfaceControl getLeash() {
            return null;
        }
        public int getMode() {
            return 0;
        }
        public int getRotationAnimation() {
            return 0;
        }
        public android.view.SurfaceControl getSnapshot() {
            return null;
        }
        public float getSnapshotLuma() {
            return 0f;
        }
        public android.graphics.Rect getStartAbsBounds() {
            return new android.graphics.Rect();
        }
        public int getStartDisplayId() {
            return 0;
        }
        public int getStartRotation() {
            return 0;
        }
        public android.app.ActivityManager.RunningTaskInfo getTaskInfo() {
            return null;
        }
        public boolean hasAllFlags(int p0) {
            return false;
        }
        public boolean hasFlags(int p0) {
            return false;
        }
        public boolean isAllowEnterPip() {
            return false;
        }
        public void setEndAbsBounds(android.graphics.Rect p0) {
        }
        public void setFlags(int p0) {
        }
        public void setLeash(android.view.SurfaceControl p0) {
        }
        public void setMode(int p0) {
        }
        public void setStartAbsBounds(android.graphics.Rect p0) {
        }
    }
    public static class Root {
        public int getDisplayId() {
            return 0;
        }
        public android.view.SurfaceControl getLeash() {
            return null;
        }
        public android.graphics.Point getOffset() {
            return new android.graphics.Point();
        }
    }
}
