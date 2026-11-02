package com.hihonor.mcs.media.avfoundation.player;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Weather uses, answering "absent" off MagicOS (no Honor code). */
public class AVFoundationPlayer {
    public AVFoundationPlayer() {
    }
    public AVFoundationPlayer(android.content.Context p0) {
    }
    public void addExternalSource(java.lang.String p0) {
    }
    public void clearAndroidSurface(android.view.Surface p0) {
    }
    public void clearExternalSource() {
    }
    public int disableAudio(boolean p0) {
        return 0;
    }
    public int disableVideo(boolean p0) {
        return 0;
    }
    public void enableFrameMode(boolean p0) {
    }
    public void enableReversePlay(boolean p0) {
    }
    public java.lang.String getAudioMime(int p0) {
        return "";
    }
    public int getAudioSessionId() {
        return 0;
    }
    public int getAudioTrackCount() {
        return 0;
    }
    public android.graphics.Bitmap getCurrentPicture() {
        return null;
    }
    public long getCurrentPosition() {
        return 0L;
    }
    public static int getDefaultThumbnailCount(long p0) {
        return 0;
    }
    public float getDownloadProgress() {
        return 0f;
    }
    public long getDuration() {
        return 0L;
    }
    public int getFrameRate() {
        return 0;
    }
    public java.lang.String getLanguage(int p0) {
        return "";
    }
    public float getPlaybackSpeed() {
        return 0f;
    }
    public int getPlayerId() {
        return 0;
    }
    public float getRotation() {
        return 0f;
    }
    public int getSelectTrack(int p0) {
        return 0;
    }
    public int getSelectTrackType(int p0) {
        return 0;
    }
    public int getState() {
        return 0;
    }
    public int getThumbnailCount() {
        return 0;
    }
    public int getTrackCount() {
        return 0;
    }
    public int getTrackId(int p0) {
        return 0;
    }
    public int getVideoHeight() {
        return 0;
    }
    public java.lang.String getVideoMime(int p0) {
        return "";
    }
    public int getVideoWidth() {
        return 0;
    }
    public float getVolume() {
        return 0f;
    }
    public boolean hasFeature(int p0) {
        return false;
    }
    public static boolean hasNetworkCache(java.lang.String p0, java.lang.String p1) {
        return false;
    }
    public boolean isBuffering() {
        return false;
    }
    public boolean isCurrentMediaItemLive() {
        return false;
    }
    public boolean isHdrVideo() {
        return false;
    }
    public boolean isLooping() {
        return false;
    }
    public boolean isPlaying() {
        return false;
    }
    public static boolean isSupportFeature(int p0) {
        return false;
    }
    public void pause() {
    }
    public int pauseDownload() {
        return 0;
    }
    public void prepare() {
    }
    public void release() {
    }
    public void releaseAsync() {
    }
    public void releaseCodec() {
    }
    public long renderNextFrame() {
        return 0L;
    }
    public void seekTo(long p0, int p1) {
    }
    public int selectTrack(int p0) {
        return 0;
    }
    public void setAndroidSurface(android.view.Surface p0) {
    }
    public int setAndroidSurfaceViewWithRet(android.view.SurfaceView p0) {
        return 0;
    }
    public int setAndroidSurfaceWithRet(android.view.Surface p0) {
        return 0;
    }
    public void setDataSource(int p0, java.lang.Long p1) {
    }
    public void setDataSource(android.net.Uri p0, android.content.Context p1) {
    }
    public void setDataSource(java.lang.String p0) {
    }
    public void setDataSourceDynamicPhoto(int p0, java.lang.Long p1, java.lang.Long p2, java.lang.String p3) {
    }
    public void setDataSourceDynamicPhoto(android.content.Context p0, android.net.Uri p1, java.lang.Long p2, java.lang.Long p3) {
    }
    public void setDataSourceStream(java.lang.String p0, java.lang.String p1, float p2) {
    }
    public void setDataSourceStreamAndHeaders(java.lang.String p0, java.lang.String p1, float p2, java.util.Map p3) {
    }
    public void setDecodeMode(int p0) {
    }
    public void setDumpFd(int p0, int p1, java.lang.String p2) {
    }
    public boolean setEndTimestamp(long p0) {
        return false;
    }
    public void setFrameRenderMode(int p0) {
    }
    public void setListener(com.hihonor.mcs.media.avfoundation.player.AVFoundationPlayer.AVFoundationPlayerListener p0) {
    }
    public void setLooping(boolean p0) {
    }
    public void setOnThumbnailListener(com.hihonor.mcs.media.avfoundation.player.AVFoundationPlayer.OnRetrieverThumbnailListener p0) {
    }
    public int setPlayDirection(boolean p0) {
        return 0;
    }
    public void setPlaybackSpeed(float p0) {
    }
    public void setPreCacheCapacity(float p0) {
    }
    public int setRenderMode(int p0) {
        return 0;
    }
    public void setTemplateSourceFromJson(java.lang.String p0, boolean p1) {
    }
    public int setThumbnailParameter(int p0, int p1, int p2, int p3) {
        return 0;
    }
    public boolean setTimeRange(long p0, long p1) {
        return false;
    }
    public void setVolume(float p0) {
    }
    public void start() {
    }
    public int startDownload(float p0) {
        return 0;
    }
    public int startRetrieve(int p0, int p1, int p2) {
        return 0;
    }
    public void stop() {
    }
    public int stopRetrieve() {
        return 0;
    }
    public interface AVFoundationPlayerListener {
    }
    public interface OnRetrieverThumbnailListener {
    }
}
