package com.heytap.log;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public class Settings {
    public Settings() {
    }
    public static class Builder {
        public Builder(android.content.Context p0, java.lang.String p1, java.lang.String p2, java.lang.String p3, com.heytap.log.Settings.IImeiProvider p4, com.heytap.log.Settings.IOpenIdProvider p5) {
        }
        public Builder() {
        }
        public com.heytap.log.Settings build(android.content.Context p0) {
            return new com.heytap.log.Settings();
        }
        public com.heytap.log.Settings.Builder consoleLogLevel(int p0) {
            return this;
        }
        public com.heytap.log.Settings.Builder fileExpireDays(int p0) {
            return this;
        }
        public com.heytap.log.Settings.Builder fileLogLevel(int p0) {
            return this;
        }
        public com.heytap.log.Settings.Builder setDebug(boolean p0) {
            return this;
        }
        public com.heytap.log.Settings.Builder setTracePkg(java.lang.String p0) {
            return this;
        }
    }
    public interface IImeiProvider {
    }
    public interface IOpenIdProvider {
    }
}
