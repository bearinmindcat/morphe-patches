package com.oplus.nearx.track;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Weather uses, answering "absent" off ColorOS (no OPPO code). */
public class TrackApi {
    public TrackApi() {
    }
    public static com.oplus.nearx.track.TrackApi.Companion Companion;
    public static com.oplus.nearx.track.TrackApi getInstance(long p0) {
        return new com.oplus.nearx.track.TrackApi();
    }
    public boolean init(com.oplus.nearx.track.TrackApi.Config p0) {
        return false;
    }
    public static void staticInitIfUninitialized(android.app.Application p0, com.oplus.nearx.track.TrackApi.StaticConfig p1) {
    }
    public void track(java.lang.String p0, java.lang.String p1, java.util.Map p2) {
    }
    public static class Companion {
        public com.oplus.nearx.track.TrackApi getInstance(long p0) {
            return new com.oplus.nearx.track.TrackApi();
        }
        public void staticInitIfUninitialized(android.app.Application p0, com.oplus.nearx.track.TrackApi.StaticConfig p1) {
        }
    }
    public static class Config {
        public Config() {
        }
        public static class Builder {
            public Builder(java.lang.String p0, java.lang.String p1) {
            }
            public com.oplus.nearx.track.TrackApi.Config build() {
                return new com.oplus.nearx.track.TrackApi.Config();
            }
        }
    }
    public static class StaticConfig {
        public StaticConfig() {
        }
        public static class Builder {
            public Builder(java.lang.String p0) {
            }
            public Builder() {
            }
            public com.oplus.nearx.track.TrackApi.StaticConfig build() {
                return new com.oplus.nearx.track.TrackApi.StaticConfig();
            }
            public com.oplus.nearx.track.TrackApi.StaticConfig.Builder defaultToDeviceProtectedStorage(boolean p0) {
                return this;
            }
            public com.oplus.nearx.track.TrackApi.StaticConfig.Builder enableLog(boolean p0) {
                return this;
            }
        }
    }
}
