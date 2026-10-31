package org.ungoogled.samsungroutines;

import android.app.AutomaticZenRule;
import android.app.NotificationManager;
import android.service.notification.Condition;
import android.util.Log;

import java.util.Collections;
import java.util.Map;

/**
 * Do not disturb rules ("modes") need notification policy access, which One UI's SystemUI user id always has; any other
 * app gets it only when the user allows it (the Do not disturb action already sends them to that setting). Without it
 * every call throws SecurityException, which crashed the app at start-up; here a refused call answers "no rules".
 */
public final class Zen {
    private static final String TAG = "UA";

    private Zen() {
    }

    private static void refused(String call, SecurityException e) {
        Log.w(TAG, "Do not disturb access refused (" + call + "): " + e.getMessage());
    }

    public static Map<String, AutomaticZenRule> getAutomaticZenRules(NotificationManager nm) {
        try {
            return nm.getAutomaticZenRules();
        } catch (SecurityException e) {
            refused("getAutomaticZenRules", e);
            return Collections.emptyMap();
        }
    }

    public static AutomaticZenRule getAutomaticZenRule(NotificationManager nm, String id) {
        try {
            return nm.getAutomaticZenRule(id);
        } catch (SecurityException e) {
            refused("getAutomaticZenRule", e);
            return null;
        }
    }

    public static String addAutomaticZenRule(NotificationManager nm, AutomaticZenRule rule) {
        try {
            return nm.addAutomaticZenRule(rule);
        } catch (SecurityException e) {
            refused("addAutomaticZenRule", e);
            return null;
        }
    }

    public static boolean updateAutomaticZenRule(NotificationManager nm, String id, AutomaticZenRule rule) {
        try {
            return nm.updateAutomaticZenRule(id, rule);
        } catch (SecurityException e) {
            refused("updateAutomaticZenRule", e);
            return false;
        }
    }

    public static boolean removeAutomaticZenRule(NotificationManager nm, String id) {
        try {
            return nm.removeAutomaticZenRule(id);
        } catch (SecurityException e) {
            refused("removeAutomaticZenRule", e);
            return false;
        }
    }

    public static void setAutomaticZenRuleState(NotificationManager nm, String id, Condition condition) {
        try {
            nm.setAutomaticZenRuleState(id, condition);
        } catch (SecurityException e) {
            refused("setAutomaticZenRuleState", e);
        }
    }
}
