package com.samsung.android.transcode.core;

/** One UI's transcoder progress callbacks. */
public class Encode {
    public interface EncodeProgressListener {
        void onStarted();

        void onProgressChanged(int percent);

        void onCompleted();
    }
}
