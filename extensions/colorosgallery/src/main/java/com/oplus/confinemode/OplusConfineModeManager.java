package com.oplus.confinemode;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Photos uses, answering "absent" off ColorOS (no OPPO code). */
public class OplusConfineModeManager {
    public OplusConfineModeManager() {
    }
    public int getConfineMode() {
        return 0;
    }
    public static com.oplus.confinemode.OplusConfineModeManager getInstance() {
        return new com.oplus.confinemode.OplusConfineModeManager();
    }
    public boolean registerConfineModeObserver(android.content.Context p0, com.oplus.confinemode.OplusConfineModeManager.ConfineModeObserver p1) {
        return false;
    }
    public void setConfineMode(int p0, boolean p1) {
    }
    public void setPermitList(int p0, int p1, java.util.List p2, boolean p3) {
    }
    public boolean unregisterConfineModeObserver(android.content.Context p0, com.oplus.confinemode.OplusConfineModeManager.ConfineModeObserver p1) {
        return false;
    }
    public interface ConfineModeObserver {
    }
}
