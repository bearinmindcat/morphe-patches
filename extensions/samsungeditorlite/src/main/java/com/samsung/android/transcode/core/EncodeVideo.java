package com.samsung.android.transcode.core;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.samsung.android.transcode.util.CodecsHelper;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;

/**
 * One UI's video transcoder (part of Samsung's media framework), rebuilt on Android's MediaExtractor, MediaCodec and
 * MediaMuxer (no Samsung code). Editor Lite saves every cut through it:
 * <ul>
 * <li>rewrite(): the cut is copied without re-encoding, from the key frame at or before its start to its end;</li>
 * <li>encode(): the cut is decoded, scaled to the asked size with OpenGL and encoded again as H.264 with AAC audio,
 * within the asked file size (Samsung Messages asks for one, in KB, for MMS).</li>
 * </ul>
 * The rotation the user picked is added to the video's own. Errors are thrown (the caller reports "can't save");
 * stop() ends the job and removes the partial file.
 */
public class EncodeVideo {
    private static final String TAG = "UA";
    private static final List<String> MUXER_AUDIO = Arrays.asList(
            MediaFormat.MIMETYPE_AUDIO_AAC, MediaFormat.MIMETYPE_AUDIO_AMR_NB, MediaFormat.MIMETYPE_AUDIO_AMR_WB);
    private static final long TIMEOUT_US = 10_000;

    private String outputPath;
    private int width;
    private int height;
    private Context context;
    private Uri inputUri;
    private String inputPath;
    private int frameRate;
    private long trimStartMs;
    private long trimEndMs = -1;
    private int rotation;
    private long maxOutputSize = -1;
    /** A rewrite that is encoded again to start where asked (see rewrite()). */
    private boolean exactCut;
    /** How far before a cut's start its key frame may be for the cut to be copied as it is. */
    private static final long MAX_KEY_FRAME_GAP_US = 1_500_000;
    private Encode.EncodeProgressListener listener;
    private volatile boolean stopped;
    private int lastProgress = -1;

    public EncodeVideo() {
    }

    // ---- set-up --------------------------------------------------------------------------------------------------

    public void initialize(String output, int width, int height, Context context, Uri uri, boolean rewrite,
                           boolean sef, int frameRate) {
        this.outputPath = output;
        this.width = width;
        this.height = height;
        this.context = context;
        this.inputUri = uri;
        this.inputPath = null;
        this.frameRate = frameRate;
    }

    public void initialize(String output, int width, int height, String input, boolean rewrite, boolean sef,
                           int frameRate) {
        this.outputPath = output;
        this.width = width;
        this.height = height;
        this.inputPath = input;
        this.inputUri = null;
        this.frameRate = frameRate;
    }

    public void setEncodingCodecs(int video, int audio) {
    }

    public void setTrimTime(long startMs, long endMs) {
        trimStartMs = Math.max(0, startMs);
        trimEndMs = endMs;
    }

    public void setRotation(int degrees) {
        rotation = degrees;
    }

    /** In KB, as Editor Lite passes it on from the caller's VIDEO_OUTPUT_SIZE. */
    public void setMaxOutputSize(int sizeKB) {
        maxOutputSize = sizeKB > 0 ? sizeKB * 1024L : -1;
    }

    public void setExportRecordingMode(int mode) {
    }

    public void setEncodeProgressListener(Encode.EncodeProgressListener listener) {
        this.listener = listener;
    }

    public void stop() {
        stopped = true;
    }

    /** How long (ms) a video of this size may be to fit in sizeKB; no limit when no size is given. */
    public static int getMaxEncodingDuration(int sizeKB, int width, int height, int audioChannels) {
        if (sizeKB <= 0) return Integer.MAX_VALUE;
        long bitsPerSecond = CodecsHelper.suggestBitRate(width, height) + audioBitRate(width, height);
        return (int) Math.min(Integer.MAX_VALUE, sizeKB * 1024L * 8L * 1000L * 92 / 100 / bitsPerSecond);
    }

    /** The AAC rate for a re-encoded video: low for MMS-sized pictures. */
    private static int audioBitRate(int width, int height) {
        return (long) width * height <= 352 * 288 ? 24_000 : 96_000;
    }

    /** Whether an MP4 file has a top-level box of this type (e.g. "moof": a fragmented MP4, which cannot be cut). */
    public static boolean findAtom(String path, String type) {
        if (path == null || type == null || type.length() != 4) return false;
        try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
            long length = file.length();
            long at = 0;
            byte[] header = new byte[8];
            for (int boxes = 0; boxes < 10_000 && at + 8 <= length; boxes++) {
                file.seek(at);
                file.readFully(header);
                long size = ((header[0] & 0xffL) << 24) | ((header[1] & 0xffL) << 16) | ((header[2] & 0xffL) << 8) | (header[3] & 0xffL);
                String name = new String(header, 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (name.equals(type)) return true;
                if (size == 1) {
                    size = file.readLong();
                } else if (size == 0) {
                    break;
                }
                if (size < 8) break;
                at += size;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }

    // ---- jobs ----------------------------------------------------------------------------------------------------

    private MediaExtractor openInput() throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        if (inputPath != null) extractor.setDataSource(inputPath);
        else extractor.setDataSource(context, inputUri, null);
        return extractor;
    }

    private static int track(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static int intOf(MediaFormat format, String key, int fallback) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void progress(long doneUs, long totalUs) {
        if (listener == null || totalUs <= 0) return;
        int percent = (int) Math.max(0, Math.min(100, doneUs * 100 / totalUs));
        if (percent != lastProgress) {
            lastProgress = percent;
            listener.onProgressChanged(percent);
        }
    }

    private void discard() {
        if (outputPath != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(outputPath).delete();
        }
    }

    /**
     * Copies the cut as it is (no re-encoding). A cut can only start at a key frame that way; where the one before the
     * cut's start is far from it (screen recordings, downloaded videos: phone cameras put one every second), the cut
     * is encoded again at the video's own size instead, so it starts where it was asked.
     */
    public void rewrite() throws IOException {
        if (listener != null) listener.onStarted();
        MediaExtractor probe = openInput();
        try {
            int video = track(probe, "video/");
            if (video >= 0) {
                probe.selectTrack(video);
                long startUs = trimStartMs * 1000L;
                probe.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                long keyUs = probe.getSampleTime();
                if (keyUs >= 0 && startUs - keyUs > MAX_KEY_FRAME_GAP_US) {
                    MediaFormat format = probe.getTrackFormat(video);
                    width = intOf(format, MediaFormat.KEY_WIDTH, width);
                    height = intOf(format, MediaFormat.KEY_HEIGHT, height);
                    exactCut = true;
                }
            }
        } finally {
            probe.release();
        }
        if (exactCut) {
            transcode();
            return;
        }
        MediaExtractor extractor = openInput();
        MediaMuxer muxer = null;
        boolean ok = false;
        try {
            int video = track(extractor, "video/");
            if (video < 0) throw new IOException("no video track");
            int audio = track(extractor, "audio/");
            MediaFormat videoFormat = extractor.getTrackFormat(video);
            if (audio >= 0 && !MUXER_AUDIO.contains(extractor.getTrackFormat(audio).getString(MediaFormat.KEY_MIME))) {
                Log.w(TAG, "audio track not copied: " + extractor.getTrackFormat(audio).getString(MediaFormat.KEY_MIME));
                audio = -1;
            }
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(((intOf(videoFormat, MediaFormat.KEY_ROTATION, 0) + rotation) % 360 + 360) % 360);
            int outVideo = muxer.addTrack(videoFormat);
            int outAudio = audio >= 0 ? muxer.addTrack(extractor.getTrackFormat(audio)) : -1;
            int bufferSize = Math.max(intOf(videoFormat, MediaFormat.KEY_MAX_INPUT_SIZE, 0), 4 * 1024 * 1024);
            if (audio >= 0) bufferSize = Math.max(bufferSize, intOf(extractor.getTrackFormat(audio), MediaFormat.KEY_MAX_INPUT_SIZE, 0));
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long startUs = trimStartMs * 1000L;
            long durationUs = videoFormat.containsKey(MediaFormat.KEY_DURATION) ? videoFormat.getLong(MediaFormat.KEY_DURATION) : Long.MAX_VALUE;
            long endUs = trimEndMs > trimStartMs ? trimEndMs * 1000L : durationUs;
            // The cut starts at the key frame at or before its start; the audio from the same moment.
            extractor.selectTrack(video);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long baseUs = extractor.getSampleTime();
            if (baseUs < 0) baseUs = startUs;
            muxer.start();
            copy(extractor, muxer, video, outVideo, baseUs, endUs, buffer, info, true);
            if (audio >= 0) {
                extractor.unselectTrack(video);
                extractor.selectTrack(audio);
                extractor.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                copy(extractor, muxer, audio, outAudio, baseUs, endUs, buffer, info, false);
            }
            if (stopped) return;
            ok = true;
        } finally {
            extractor.release();
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (RuntimeException e) {
                    ok = false;
                }
                muxer.release();
            }
            if (!ok) discard();
        }
        if (listener != null) listener.onCompleted();
    }

    private void copy(MediaExtractor extractor, MediaMuxer muxer, int track, int outTrack, long baseUs, long endUs,
                      ByteBuffer buffer, MediaCodec.BufferInfo info, boolean video) throws IOException {
        long total = Math.max(1, endUs - baseUs);
        while (!stopped) {
            int index = extractor.getSampleTrackIndex();
            if (index < 0) break;
            long time = extractor.getSampleTime();
            if (index != track) {
                extractor.advance();
                continue;
            }
            if (time > endUs) break;
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            if (time >= baseUs) {
                info.set(0, size, time - baseUs, (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(outTrack, buffer, info);
            }
            if (video) progress((time - baseUs) * 9 / 10, total);
            if (!extractor.advance()) break;
        }
    }

    /** Decodes the cut, scales it to width x height and encodes it as H.264; the audio is encoded again as AAC. */
    public void encode() throws IOException {
        if (listener != null) listener.onStarted();
        transcode();
    }

    private void transcode() throws IOException {
        MediaExtractor extractor = openInput();
        MediaMuxer muxer = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        GlScaler gl = null;
        boolean ok = false;
        try {
            int video = track(extractor, "video/");
            if (video < 0) throw new IOException("no video track");
            MediaFormat in = extractor.getTrackFormat(video);
            int audio = track(extractor, "audio/");
            int srcWidth = intOf(in, MediaFormat.KEY_WIDTH, width);
            int srcHeight = intOf(in, MediaFormat.KEY_HEIGHT, height);
            int outWidth = width > 0 ? width : srcWidth;
            int outHeight = height > 0 ? height : srcHeight;
            // The asked size may be the shown one: keep the stored frame's orientation (the rotation stays a hint).
            if ((srcWidth >= srcHeight) != (outWidth >= outHeight)) {
                int t = outWidth;
                outWidth = outHeight;
                outHeight = t;
            }
            outWidth &= ~1;
            outHeight &= ~1;
            long durationUs = in.containsKey(MediaFormat.KEY_DURATION) ? in.getLong(MediaFormat.KEY_DURATION) : 0;
            long startUs = trimStartMs * 1000L;
            long endUs = trimEndMs > trimStartMs ? trimEndMs * 1000L : (durationUs > 0 ? durationUs : Long.MAX_VALUE);
            int fps = frameRate > 0 ? frameRate : intOf(in, MediaFormat.KEY_FRAME_RATE, 30);
            int bitRate = CodecsHelper.suggestBitRate(outWidth, outHeight);
            int audioRate = audioBitRate(outWidth, outHeight);
            if (exactCut) {
                // About what the video had: twice the usual rate, and the full audio rate.
                bitRate = (int) Math.min(40_000_000L, bitRate * 2L);
                audioRate = 128_000;
            }
            if (maxOutputSize > 0 && endUs != Long.MAX_VALUE) {
                double seconds = Math.max(0.5, (endUs - startUs) / 1e6);
                long budget = (long) (maxOutputSize * 8L * 0.92 / seconds) - (audio >= 0 ? audioRate : 0);
                bitRate = (int) Math.max(32_000, Math.min(bitRate, budget));
            }
            // The audio first, into memory (small at these rates): the muxer needs both tracks before it starts.
            EncodedAudio sound = audio >= 0 ? encodeAudio(audio, startUs, endUs, audioRate) : null;

            MediaFormat out = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight);
            out.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            out.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            out.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            out.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(out, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            gl = new GlScaler(encoder.createInputSurface(), outWidth, outHeight);
            encoder.start();
            decoder = MediaCodec.createDecoderByType(in.getString(MediaFormat.KEY_MIME));
            in.setInteger(MediaFormat.KEY_ROTATION, 0);
            decoder.configure(in, gl.decoderSurface(), null, 0);
            decoder.start();

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(((intOf(extractor.getTrackFormat(video), MediaFormat.KEY_ROTATION, 0) + rotation) % 360 + 360) % 360);
            extractor.selectTrack(video);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean decodeDone = false;
            boolean encodeDone = false;
            int outVideo = -1;
            int outAudio = -1;
            boolean muxing = false;
            long total = Math.max(1, (endUs == Long.MAX_VALUE ? durationUs : endUs) - startUs);
            while (!encodeDone && !stopped) {
                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = extractor.readSampleData(buffer, 0);
                        long time = extractor.getSampleTime();
                        if (size < 0 || time > endUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, time, 0);
                            extractor.advance();
                        }
                    }
                }
                if (!decodeDone) {
                    int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (index >= 0) {
                        boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean show = info.size > 0 && info.presentationTimeUs >= startUs && info.presentationTimeUs <= endUs;
                        long time = info.presentationTimeUs;
                        decoder.releaseOutputBuffer(index, show);
                        if (show) {
                            gl.drawFrame((time - startUs) * 1000L);
                            progress(time - startUs, total);
                        }
                        if (end) {
                            encoder.signalEndOfInputStream();
                            decodeDone = true;
                        }
                    }
                }
                while (true) {
                    int index = encoder.dequeueOutputBuffer(info, decodeDone ? TIMEOUT_US : 0);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outVideo = muxer.addTrack(encoder.getOutputFormat());
                        if (sound != null) outAudio = muxer.addTrack(sound.format);
                        muxer.start();
                        muxing = true;
                        continue;
                    }
                    if (index < 0) continue;
                    ByteBuffer data = encoder.getOutputBuffer(index);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxing) muxer.writeSampleData(outVideo, data, info);
                    encoder.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encodeDone = true;
                        break;
                    }
                }
            }
            if (stopped) return;
            if (!muxing) throw new IOException("encoder produced nothing");
            if (sound != null) {
                for (int i = 0; i < sound.samples.size(); i++) {
                    byte[] sample = sound.samples.get(i);
                    MediaCodec.BufferInfo at = sound.infos.get(i);
                    muxer.writeSampleData(outAudio, ByteBuffer.wrap(sample), at);
                }
            }
            ok = !stopped;
        } finally {
            release(decoder);
            release(encoder);
            if (gl != null) gl.release();
            extractor.release();
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (RuntimeException e) {
                    ok = false;
                }
                muxer.release();
            }
            if (!ok) discard();
        }
        if (listener != null) listener.onCompleted();
    }

    private static final class EncodedAudio {
        MediaFormat format;
        final java.util.ArrayList<byte[]> samples = new java.util.ArrayList<>();
        final java.util.ArrayList<MediaCodec.BufferInfo> infos = new java.util.ArrayList<>();
    }

    /** Decodes the audio track's part of the cut and encodes it as AAC; null when the track cannot be decoded. */
    private EncodedAudio encodeAudio(int track, long startUs, long endUs, int bitRate) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        EncodedAudio out = new EncodedAudio();
        try {
            extractor = openInput();
            MediaFormat in = extractor.getTrackFormat(track);
            extractor.selectTrack(track);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            decoder = MediaCodec.createDecoderByType(in.getString(MediaFormat.KEY_MIME));
            decoder.configure(in, null, null, 0);
            decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean decodeDone = false;
            boolean encodeDone = false;
            int rate = intOf(in, MediaFormat.KEY_SAMPLE_RATE, 44100);
            int channels = intOf(in, MediaFormat.KEY_CHANNEL_COUNT, 2);
            byte[] pending = null;
            int pendingOffset = 0;
            long written = 0;
            boolean endSent = false;
            while (!encodeDone && !stopped) {
                if (!inputDone) {
                    int index = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (index >= 0) {
                        int size = extractor.readSampleData(decoder.getInputBuffer(index), 0);
                        long time = extractor.getSampleTime();
                        if (size < 0 || time > endUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, time, 0);
                            extractor.advance();
                        }
                    }
                }
                if (!decodeDone && pending == null) {
                    int index = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = decoder.getOutputFormat();
                        rate = intOf(f, MediaFormat.KEY_SAMPLE_RATE, rate);
                        channels = intOf(f, MediaFormat.KEY_CHANNEL_COUNT, channels);
                    } else if (index >= 0) {
                        if (encoder == null) {
                            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels);
                            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                            f.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
                            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024);
                            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                            encoder.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                            encoder.start();
                        }
                        boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (info.size > 0 && info.presentationTimeUs >= startUs && info.presentationTimeUs <= endUs) {
                            ByteBuffer pcm = decoder.getOutputBuffer(index);
                            pcm.position(info.offset).limit(info.offset + info.size);
                            pending = new byte[info.size];
                            pcm.get(pending);
                            pendingOffset = 0;
                        }
                        decoder.releaseOutputBuffer(index, false);
                        if (end) decodeDone = true;
                    }
                }
                if (encoder != null) {
                    if (pending != null || (decodeDone && !endSent)) {
                        int index = encoder.dequeueInputBuffer(TIMEOUT_US);
                        if (index >= 0) {
                            ByteBuffer target = encoder.getInputBuffer(index);
                            if (pending != null) {
                                int n = Math.min(target.remaining(), pending.length - pendingOffset);
                                target.put(pending, pendingOffset, n);
                                // Continuous time from the first sample of the cut (16-bit PCM).
                                long frames = written / (2L * channels);
                                encoder.queueInputBuffer(index, 0, n, frames * 1_000_000L / rate, 0);
                                written += n;
                                pendingOffset += n;
                                if (pendingOffset >= pending.length) pending = null;
                            } else {
                                encoder.queueInputBuffer(index, 0, 0, written / (2L * channels) * 1_000_000L / rate,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                endSent = true;
                            }
                        }
                    }
                    while (true) {
                        int index = encoder.dequeueOutputBuffer(info, 0);
                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            out.format = encoder.getOutputFormat();
                            continue;
                        }
                        if (index < 0) continue;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                            ByteBuffer data = encoder.getOutputBuffer(index);
                            data.position(info.offset).limit(info.offset + info.size);
                            byte[] copy = new byte[info.size];
                            data.get(copy);
                            MediaCodec.BufferInfo at = new MediaCodec.BufferInfo();
                            at.set(0, copy.length, info.presentationTimeUs, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            out.samples.add(copy);
                            out.infos.add(at);
                        }
                        encoder.releaseOutputBuffer(index, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encodeDone = true;
                            break;
                        }
                    }
                } else if (decodeDone) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "audio not encoded: " + e);
            return null;
        } finally {
            release(decoder);
            release(encoder);
            if (extractor != null) extractor.release();
        }
        return out.format != null && !out.samples.isEmpty() ? out : null;
    }

    private static void release(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // Not started.
        }
        codec.release();
    }

    /**
     * Draws each decoded frame (from a SurfaceTexture) onto the encoder's input surface at the output size: the usual
     * MediaCodec decode-to-encode path through OpenGL ES 2.
     */
    private static final class GlScaler implements SurfaceTexture.OnFrameAvailableListener {
        private static final String VERTEX = "uniform mat4 uTex;\n"
                + "attribute vec4 aPos;\nattribute vec4 aTex;\nvarying vec2 vTex;\n"
                + "void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }\n";
        private static final String FRAGMENT = "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\nvarying vec2 vTex;\nuniform samplerExternalOES sTex;\n"
                + "void main() { gl_FragColor = texture2D(sTex, vTex); }\n";
        private static final float[] QUAD = {
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
        };

        private final EGLDisplay display;
        private final EGLContext context;
        private final EGLSurface surface;
        private final Surface encoderInput;
        private final int width;
        private final int height;
        private final int texture;
        private final int program;
        private final SurfaceTexture surfaceTexture;
        private final Surface decoderOutput;
        private final HandlerThread callbacks = new HandlerThread("EncodeVideo frames");
        private final Object lock = new Object();
        private boolean frameAvailable;
        private final float[] matrix = new float[16];
        private final FloatBuffer quad;

        GlScaler(Surface encoderInput, int width, int height) throws IOException {
            this.encoderInput = encoderInput;
            this.width = width;
            this.height = height;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IOException("no EGL");
            int[] attributes = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGLExt.EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE,
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                throw new IOException("no recordable EGL config");
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderInput, new int[]{EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) throw new IOException("no EGL surface");
            EGL14.eglMakeCurrent(display, surface, surface, context);

            program = link(compile(GLES20.GL_VERTEX_SHADER, VERTEX), compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texture = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(QUAD).position(0);
            callbacks.start();
            surfaceTexture = new SurfaceTexture(texture);
            surfaceTexture.setOnFrameAvailableListener(this, new Handler(callbacks.getLooper()));
            decoderOutput = new Surface(surfaceTexture);
        }

        Surface decoderSurface() {
            return decoderOutput;
        }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            synchronized (lock) {
                frameAvailable = true;
                lock.notifyAll();
            }
        }

        void drawFrame(long timeNs) throws IOException {
            synchronized (lock) {
                long until = System.currentTimeMillis() + 2500;
                while (!frameAvailable) {
                    long wait = until - System.currentTimeMillis();
                    if (wait <= 0) throw new IOException("decoder frame timed out");
                    try {
                        lock.wait(wait);
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                }
                frameAvailable = false;
            }
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(matrix);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            int position = GLES20.glGetAttribLocation(program, "aPos");
            int coordinate = GLES20.glGetAttribLocation(program, "aTex");
            quad.position(0);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(position);
            quad.position(2);
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(coordinate);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGLExt.eglPresentationTimeANDROID(display, surface, timeNs);
            EGL14.eglSwapBuffers(display, surface);
        }

        private static int compile(int type, String source) throws IOException {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IOException("shader: " + GLES20.glGetShaderInfoLog(shader));
            return shader;
        }

        private static int link(int vertex, int fragment) throws IOException {
            int program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, vertex);
            GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IOException("program: " + GLES20.glGetProgramInfoLog(program));
            return program;
        }

        void release() {
            try {
                decoderOutput.release();
                surfaceTexture.release();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglTerminate(display);
            encoderInput.release();
            callbacks.quitSafely();
        }
    }
}
