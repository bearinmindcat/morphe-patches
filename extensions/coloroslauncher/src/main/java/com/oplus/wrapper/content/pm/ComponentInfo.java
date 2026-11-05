package com.oplus.wrapper.content.pm;

/** Generated stand-in (.github/scripts/coloros-launcher): names and signatures OnePlus System Launcher uses, answering "absent" off ColorOS and on Android versions without them (no OPPO or Google code). */
public class ComponentInfo {
    public ComponentInfo(android.content.pm.ComponentInfo p0) {
        org.ungoogled.coloroslauncher.ColorOS.wrap(this, p0);
    }
    public android.content.ComponentName getComponentName() {
        android.content.pm.ComponentInfo c = org.ungoogled.coloroslauncher.ColorOS.wrapped(this, android.content.pm.ComponentInfo.class); return c == null ? null : new android.content.ComponentName(c.packageName, c.name);
    }
}
