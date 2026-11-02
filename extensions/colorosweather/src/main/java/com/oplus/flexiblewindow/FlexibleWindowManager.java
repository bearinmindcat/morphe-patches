package com.oplus.flexiblewindow;

/** Generated stand-in (.github/scripts/coloros-standins): names and signatures OnePlus Weather uses, answering "absent" off ColorOS (no OPPO code). */
public class FlexibleWindowManager {
    public FlexibleWindowManager() {
    }
    public int getFlexibleWindowState(android.app.Activity p0) {
        return 0;
    }
    public static com.oplus.flexiblewindow.FlexibleWindowManager getInstance() {
        return new com.oplus.flexiblewindow.FlexibleWindowManager();
    }
    public static boolean isFlexibleActivity(android.content.res.Configuration p0) {
        return false;
    }
    public static boolean isFlexibleActivitySuitable(android.content.res.Configuration p0) {
        return false;
    }
    public android.os.Bundle setExtraBundle(android.app.ActivityOptions p0, android.os.Bundle p1) {
        return p0 != null ? p0.toBundle() : new android.os.Bundle();
    }
}
