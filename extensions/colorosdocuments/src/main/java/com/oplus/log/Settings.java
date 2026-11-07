package com.oplus.log;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Documents uses, answering "absent" off ColorOS (no OPPO code). */
public class Settings {
    public Settings() {
    }
    public void setEnableOBus(boolean p0) {
    }
    public static class Builder {
        public Builder(java.lang.String p0, java.lang.String p1, java.lang.String p2, com.oplus.log.Settings.IImeiProvider p3, com.oplus.log.Settings.IOpenIdProvider p4) {
        }
        public Builder() {
        }
        public com.oplus.log.Settings build(android.content.Context p0) {
            return new com.oplus.log.Settings();
        }
        public com.oplus.log.Settings.Builder consoleLogLevel(int p0) {
            return this;
        }
        public com.oplus.log.Settings.Builder fileLogLevel(int p0) {
            return this;
        }
        public com.oplus.log.Settings.Builder setTracePkg(java.lang.String p0) {
            return this;
        }
    }
    public interface IImeiProvider {
    }
    public interface IOpenIdProvider {
    }
}
