package com.xiaomi.analytics;

/**
 * Stand-in (.github/scripts/xiaomi-launcher/overrides): an analytics event of Xiaomi's analytics SDK, an Action like the
 * launcher's other events (it hands one where an Action is expected). Nothing is collected. Names only, no Xiaomi code.
 */
public class AdAction extends com.xiaomi.analytics.Action {
    public com.xiaomi.analytics.Action addParam(java.lang.String p0, long p1) {
        return this;
    }
    public com.xiaomi.analytics.Action addParam(java.lang.String p0, java.lang.String p1) {
        return this;
    }
}
