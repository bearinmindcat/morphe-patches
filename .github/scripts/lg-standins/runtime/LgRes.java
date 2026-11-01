package org.ungoogled.APP;

import android.content.Context;
import android.content.res.Resources;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resource ids APP_NAME's code names by LG's framework id (a dialog layout, a theme, a size): the patch rewrites each
 * such literal to a lookup here of the resource the port added for it ("type/name", by name: added resources get their
 * ids only when the APK is built) or of one of Android's ("android:type/name", ids that differ between Android
 * versions). Unknown names answer 0, as LG's ids do off LG's ROM. Lookups by name in LG's framework package
 * (getIdentifier(name, type, "com.lge")) come here too and get the same answers.
 */
public final class LgRes {
    private static final ConcurrentHashMap<String, Integer> IDS = new ConcurrentHashMap<>();
    /** LG framework resources looked up by name ("type/name" in "com.lge") and the resource that stands in for each. */
    private static final Map<String, String> LG_NAMES = new HashMap<>();
    static {
        LG_NAMES.put("layout/dialog_c_1", "layout/lg_dialog_c_1");
        LG_NAMES.put("layout/dialog_c_2", "layout/lg_dialog_c_2");
        LG_NAMES.put("layout/dialog_c_4", "layout/lg_dialog_c_4");
        LG_NAMES.put("layout/dialog_c_11", "layout/lg_dialog_c_11");
        LG_NAMES.put("layout/dialog_c_15", "layout/lg_dialog_c_15");
        LG_NAMES.put("layout/dialog_c_21", "layout/lg_dialog_c_21");
        LG_NAMES.put("layout/dialog_c_22", "layout/lg_dialog_c_22");
        LG_NAMES.put("layout/dialog_c_frame2", "layout/lg_dialog_c_frame2");
        LG_NAMES.put("layout/dialog_init_guide", "layout/lg_dialog_init_guide");
        LG_NAMES.put("layout/alert_dialog_material", "layout/lg_alert_dialog_material");
        LG_NAMES.put("drawable/floating_action_button_add", "drawable/lg_floating_action_button_add");
        LG_NAMES.put("drawable/no_contents_button_add", "drawable/lg_no_contents_button_add");
        LG_NAMES.put("drawable/ic_dialog_alert_material", "android:drawable/ic_dialog_alert");
        // LG's white (light) dialog themes: Android's light ones.
        LG_NAMES.put("style/Theme.LGE.White.Dialog.Alert", "android:style/Theme.DeviceDefault.Light.Dialog.Alert");
        LG_NAMES.put("style/Theme.LGE.White.Dialog.MinWidth.Initial", "android:style/Theme.DeviceDefault.Light.Dialog.MinWidth");
    }
    private static Context context;

    private LgRes() {
    }

    private static Context context() {
        if (context == null) {
            try {
                context = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }
        return context;
    }

    /**
     * Resources.getIdentifier for the app: names in LG's framework package ("com.lge") get their stand-in (or 0, as off
     * LG's ROM); everything else as Android answers.
     */
    public static int getIdentifier(Resources resources, String name, String type, String pkg) {
        String lgName = null;
        if ("com.lge".equals(pkg) && name != null && type != null) lgName = type + "/" + name;
        else if (name != null && name.startsWith("com.lge:")) lgName = name.substring("com.lge:".length());
        if (lgName != null) {
            String answer = LG_NAMES.get(lgName);
            return answer == null ? 0 : id(answer);
        }
        return resources.getIdentifier(name, type, pkg);
    }

    public static int id(String name) {
        Integer known = IDS.get(name);
        if (known != null) return known;
        Context c = context();
        if (c == null) return 0;
        int slash = name.indexOf('/');
        int colon = name.indexOf(':');
        String pkg = colon >= 0 && colon < slash ? name.substring(0, colon) : c.getPackageName();
        String type = name.substring(colon >= 0 && colon < slash ? colon + 1 : 0, slash);
        int id = c.getResources().getIdentifier(name.substring(slash + 1), type, pkg);
        IDS.put(name, id);
        return id;
    }
}
