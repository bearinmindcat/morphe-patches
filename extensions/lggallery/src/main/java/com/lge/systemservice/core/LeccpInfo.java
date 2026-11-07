package com.lge.systemservice.core;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG Gallery uses, answering "absent" off LG's ROM (no LG code). */
public class LeccpInfo {
    public static class Action {
        public com.lge.systemservice.core.LeccpInfo.Action.PlayAction playAction;
        public int type;
        public static class PlayAction {
            public com.lge.systemservice.core.LeccpInfo.MediaInfo mediaInfo;
            public int state;
            public int stopReason;
        }
    }
    public static class ActionRequest {
        public int actionType;
        public static class PlayRequest {
            public PlayRequest() {
            }
            public void addMediaInfo(com.lge.systemservice.core.LeccpInfo.MediaInfo p0) {
            }
            public com.lge.systemservice.core.LeccpInfo.ActionRequest getActionRequest() {
                return null;
            }
            public void setPlayActionType(int p0) {
            }
        }
    }
    public static class Card {
        public java.util.List actions;
        public int connectionState;
        public int detailState;
        public int deviceType;
        public java.lang.String id;
        public java.lang.String name;
        public int serviceType;
    }
    public static class MediaInfo {
        public MediaInfo(android.net.Uri p0) {
        }
        public java.lang.String getId() {
            return "";
        }
        public android.net.Uri getUri() {
            return null;
        }
        public void setMediaType(int p0) {
        }
        public void setMimeType(java.lang.String p0) {
        }
    }
    public static class Status {
    }
}
