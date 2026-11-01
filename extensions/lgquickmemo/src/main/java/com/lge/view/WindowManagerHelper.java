package com.lge.view;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses, answering "absent" off LG's ROM (no LG code). */
public class WindowManagerHelper {
    public static void addView(android.view.View p0, android.view.WindowManager.LayoutParams p1, android.view.Display p2, android.view.Window p3) {
        
        android.content.Context c = p0.getContext();
        android.app.Activity activity = null;
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof android.app.Activity) { activity = (android.app.Activity) c; break; }
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        android.view.WindowManager wm = p3 != null ? p3.getWindowManager() : activity != null ? activity.getWindowManager()
                : (android.view.WindowManager) p0.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
        if (p1 != null && activity != null && p1.type >= android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                && activity.getWindow().getDecorView().getWindowToken() != null) {
            p1.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
            p1.token = activity.getWindow().getDecorView().getWindowToken();
        }
        wm.addView(p0, p1);
    }
    public static int getLGSystemUiVisibility() {
        return 0;
    }
    public static int getSystemBarShownState() {
        return 0;
    }
    public static void removeView(android.view.View p0, boolean p1) {
        
        android.view.WindowManager wm = (android.view.WindowManager) p0.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
        if (p1) wm.removeViewImmediate(p0); else wm.removeView(p0);
    }
    public static void updateViewLayout(android.view.View p0, android.view.ViewGroup.LayoutParams p1) {
        
        if (p1 instanceof android.view.WindowManager.LayoutParams && p0.getLayoutParams() instanceof android.view.WindowManager.LayoutParams) {
            android.view.WindowManager.LayoutParams now = (android.view.WindowManager.LayoutParams) p0.getLayoutParams();
            ((android.view.WindowManager.LayoutParams) p1).type = now.type;
            ((android.view.WindowManager.LayoutParams) p1).token = now.token;
        }
        ((android.view.WindowManager) p0.getContext().getSystemService(android.content.Context.WINDOW_SERVICE)).updateViewLayout(p0, p1);
    }
}
