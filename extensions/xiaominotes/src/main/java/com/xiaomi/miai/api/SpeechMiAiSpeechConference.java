package com.xiaomi.miai.api;

/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures Xiaomi Notes uses, answering "absent" off HyperOS (no Xiaomi code). */
public class SpeechMiAiSpeechConference {
    public static class ConferenceServiceInfoUpdate extends com.xiaomi.miai.api.common.EventPayload {
        public ConferenceServiceInfoUpdate(boolean p0, boolean p1, boolean p2) {
        }
    }
    public static class ConferenceServiceResult {
        public java.lang.String getDetectedLang() {
            return "";
        }
        public int getSentenceId() {
            return 0;
        }
        public java.util.List getSentences() {
            return new java.util.ArrayList<>();
        }
        public boolean isSentenceCompleted() {
            return false;
        }
    }
    public static class SpeechConferenceVorResult {
        public java.util.List getVorResults() {
            return new java.util.ArrayList<>();
        }
    }
    public static class VorResult {
        public int getSentenceId() {
            return 0;
        }
        public java.lang.String getVorRole() {
            return "";
        }
    }
}
