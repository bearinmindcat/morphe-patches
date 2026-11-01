package com.xiaomi.miai.api;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Notes uses, answering "absent" off HyperOS (no Xiaomi code). */
public class SpeechMiAiSpeechAsr {
    public static class AsrCodec {
        public static com.xiaomi.miai.api.SpeechMiAiSpeechAsr.AsrCodec pcm;
    }
    public static class Sentence {
        public java.lang.String getLang() {
            return "";
        }
        public java.lang.String getText() {
            return "";
        }
    }
    public static class SpeechRecognize extends com.xiaomi.miai.api.common.EventPayload {
        public SpeechRecognize(java.lang.String p0, com.xiaomi.miai.api.SpeechMiAiSpeechAsr.AsrCodec p1, int p2, int p3, int p4, java.util.List p5) {
        }
        public SpeechRecognize() {
        }
        public com.xiaomi.miai.api.SpeechMiAiSpeechAsr.SpeechRecognize setDeviceType(java.lang.String p0) {
            return this;
        }
    }
}
