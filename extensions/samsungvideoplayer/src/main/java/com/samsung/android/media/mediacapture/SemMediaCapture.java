package com.samsung.android.media.mediacapture;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures Samsung Video Player uses, answering "absent" off One UI (no Samsung code). */
public class SemMediaCapture {
    public SemMediaCapture() {
    }
    public android.graphics.Bitmap getCaptureFrame(int p0) {
        return null;
    }
    public int getPositionForPreview() {
        return 0;
    }
    public float getProgressForCapture() {
        return 0f;
    }
    public void pausePlayback() {
    }
    public void prepare() {
    }
    public void release() {
    }
    public void reset() {
    }
    public void seekForPreview(int p0) {
    }
    public void setAudioVolumeFade(int p0, int p1, int p2, int p3, int p4) {
    }
    public void setBackgroundMusic(com.samsung.android.media.SemBackgroundMusic p0) {
    }
    public void setDataSource(android.content.Context p0, android.net.Uri p1) {
    }
    public void setDataSource(java.io.FileDescriptor p0) {
    }
    public void setDataSource(java.lang.String p0) {
    }
    public void setDisplay(android.view.SurfaceHolder p0) {
    }
    public void setDynamicViewingConfigurations(java.util.List p0) {
    }
    public void setOnDecodingCompletionListener(com.samsung.android.media.mediacapture.SemMediaCapture.OnDecodingCompletionListener p0) {
    }
    public void setOnDecodingUpdatedListener(com.samsung.android.media.mediacapture.SemMediaCapture.OnDecodingUpdatedListener p0) {
    }
    public void setOnErrorListener(com.samsung.android.media.mediacapture.SemMediaCapture.OnErrorListener p0) {
    }
    public void setOnPlaybackCompletionListener(com.samsung.android.media.mediacapture.SemMediaCapture.OnPlaybackCompletionListener p0) {
    }
    public void setOnPreparedListener(com.samsung.android.media.mediacapture.SemMediaCapture.OnPreparedListener p0) {
    }
    public void setOnRecordingCompletionListener(com.samsung.android.media.mediacapture.SemMediaCapture.OnRecordingCompletionListener p0) {
    }
    public void setOnRenderingStartedListener(com.samsung.android.media.mediacapture.SemMediaCapture.OnRenderingStartedListener p0) {
    }
    public void setOutputFile(java.io.FileDescriptor p0) {
    }
    public void setParameter(int p0, int p1) {
    }
    public void setStartEndTime(int p0, int p1) {
    }
    public void setStartEndTimeForTrimming(int p0, int p1) {
    }
    public void startCapture() {
    }
    public void startPlayback() {
    }
    public void stopCapture() {
    }
    public static class DynamicViewingConfiguration {
        public DynamicViewingConfiguration(com.samsung.android.media.mediacapture.SemMediaCapture p0, int p1, int p2, float p3) {
        }
    }
    public interface OnDecodingCompletionListener {
    }
    public interface OnDecodingUpdatedListener {
    }
    public interface OnErrorListener {
    }
    public interface OnPlaybackCompletionListener {
    }
    public interface OnPreparedListener {
    }
    public interface OnRecordingCompletionListener {
    }
    public interface OnRenderingStartedListener {
    }
}
