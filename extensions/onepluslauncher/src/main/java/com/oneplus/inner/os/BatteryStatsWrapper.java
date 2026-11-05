package com.oneplus.inner.os;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Launcher uses, answering "absent" off ColorOS (no OPPO code). */
public class BatteryStatsWrapper {
    public int getDischargeAmount(int p0) {
        return 0;
    }
    public static class TimerWrapper {
        public long getTotalTimeLocked(long p0, int p1) {
            return 0L;
        }
    }
    public static class UidWrapper {
        public com.oneplus.inner.os.BatteryStatsWrapper.TimerWrapper getForegroundActivityTimer() {
            return null;
        }
        public com.oneplus.inner.os.BatteryStatsWrapper.TimerWrapper getForegroundServiceTimer() {
            return null;
        }
        public long getProcessStateTime(int p0, long p1, int p2) {
            return 0L;
        }
        public int getUid() {
            return 0;
        }
    }
}
