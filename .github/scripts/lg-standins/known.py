# Shared answers for LG's framework (the com.lge.* classes of LG's ROM, its optional shared libraries such as
# com.lge.sui and com.lge.lgdynamicactionbar, and LG's additions inside android.*), read by ../xiaomi-standins/gen.py
# through gen.py next to this file. Keys are dex-style like the Xiaomi file: "Lowner;->name:type" (field initializer,
# a Java expression) or "Lowner;->name(params)return" (method body, Java statements; parameters are p0, p1, ...; RUNTIME
# is the app's runtime package, see runtime/). Off LG's ROM the apps should behave as on a phone without LG's
# framework: no LG UX version, no LG hardware (rollable or dual screens, pen panel) or system services, Android's own
# answers where LG only wraps them.
import os

HEADER = ('Generated stand-in (.github/scripts/lg-standins): names and signatures {app} uses, answering "absent" '
          'off LG\'s ROM (no LG code).')

# LG's framework: its own packages and the classes it adds inside android.* (every class an app names there that the
# phone lacks is LG's: ActivityManagerEx, RollableDisplayManager, TopButtonLayout, ...).
ONLY = r'^L(com/lge/|android/)'

RUNTIME_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'runtime')

# Stand-ins that extend an Android class keep Android's own members and constructors (see ../honor-standins), and
# widgets of LG's optional UI libraries that only layouts name get LayoutInflater's constructors.
SDK_BASES = True
INFLATED_ONLY = r'^Lcom/lge/(sui|lgdynamicactionbar|blurengine2)/'

FIELDS = {
    # LG's display-shape feature string: no special shape.
    'Lcom/lge/config/Features;->LGE_DISPLAY_SHAPE:Ljava/lang/String;': '""',
}

METHODS = {
    # LG's runtime-permission helper (it shows LG's guide first, then asks): Android's own request, with the request
    # code LG's helper uses (apps handle 10000 in onRequestPermissionsResult). True while something is being asked.
    'Lcom/lge/app/permission/RequestPermissionsHelper;->requestPermissionsIfNeeded(Landroid/app/Activity;[Ljava/lang/String;Lcom/lge/app/permission/GuideUiProvider;)Z': """
        java.util.List<String> need = new java.util.ArrayList<>();
        for (String p : p1) if (p0.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) need.add(p);
        if (need.isEmpty()) return false;
        p0.requestPermissions(need.toArray(new String[0]), 10000);
        return true;""",
    # LG's system services: a stand-in of the manager each name gives on LG's ROM (its answers say "absent": no pen
    # panel, no clip tray), not null, which every caller dereferences; unknown names stay null.
    'Lcom/lge/systemservice/core/LGContext;->getLGSystemService(Ljava/lang/String;)Ljava/lang/Object;': """
        String type;
        switch (p0 == null ? "" : p0) {
            case "TouchHalInterfaceService": type = "com.lge.systemservice.core.halinterface.TouchHalManager"; break;
            case "cliptray": type = "com.lge.systemservice.core.CliptrayManager"; break;
            case "osservice": type = "com.lge.systemservice.core.OsManager"; break;
            default: return null;
        }
        try { return Class.forName(type).getDeclaredConstructor().newInstance(); } catch (ReflectiveOperationException | LinkageError e) { return null; }""",
    # LG's window helper: system apps add their popups (pen tool panels, ...) as overlay windows (type 2038, which an
    # installed app may not add without "Display over other apps"). Over one of the app's own screens they become a
    # panel of that screen's window (no permission needed); elsewhere they stay what they were. Updates keep the type
    # the window was added with (Android refuses a change).
    'Lcom/lge/view/WindowManagerHelper;->addView(Landroid/view/View;Landroid/view/WindowManager$LayoutParams;Landroid/view/Display;Landroid/view/Window;)V': """
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
        wm.addView(p0, p1);""",
    'Lcom/lge/view/WindowManagerHelper;->removeView(Landroid/view/View;Z)V': """
        android.view.WindowManager wm = (android.view.WindowManager) p0.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
        if (p1) wm.removeViewImmediate(p0); else wm.removeView(p0);""",
    'Lcom/lge/view/WindowManagerHelper;->updateViewLayout(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V': """
        if (p1 instanceof android.view.WindowManager.LayoutParams && p0.getLayoutParams() instanceof android.view.WindowManager.LayoutParams) {
            android.view.WindowManager.LayoutParams now = (android.view.WindowManager.LayoutParams) p0.getLayoutParams();
            ((android.view.WindowManager.LayoutParams) p1).type = now.type;
            ((android.view.WindowManager.LayoutParams) p1).token = now.token;
        }
        ((android.view.WindowManager) p0.getContext().getSystemService(android.content.Context.WINDOW_SERVICE)).updateViewLayout(p0, p1);""",
    # LG's feature table: no blur effects (level "0"; apps read it with get()), no special display shape.
    'Lcom/lge/config/Features2;->blur_level()Ljava/util/Optional;': 'return java.util.Optional.of("0");',
    'Lcom/lge/config/Features2;->lge_display_shape()Ljava/util/Optional;': 'return java.util.Optional.empty();',
    # LG's icon theming: the icon as it is.
    'Lcom/lge/content/pm/PackageManagerEx;->getIconDrawableAsTheme(Landroid/content/Context;Landroid/graphics/drawable/Drawable;Ljava/lang/String;)Landroid/graphics/drawable/Drawable;': 'return p1;',
    'Lcom/lge/content/pm/PackageManagerEx;->getIconDrawableAsIconFrameTheme(Landroid/content/Context;Landroid/graphics/drawable/Drawable;Ljava/lang/String;I)Landroid/graphics/drawable/Drawable;': 'return p1;',
    'Lcom/lge/content/pm/PackageManagerEx;->loadUnbadgedFramedIcon(Landroid/content/Context;Landroid/content/pm/PackageItemInfo;Landroid/content/pm/ApplicationInfo;)Landroid/graphics/drawable/Drawable;':
        'return p1 == null ? null : p1.loadUnbadgedIcon(p0.getPackageManager());',
    # LG's device policy (MDM): no policy, so the microphone and screen capture are allowed.
    'Lcom/lge/mdm/LGMDMManager;->getAllowMicrophone(Landroid/content/ComponentName;)Z': 'return true;',
    'Lcom/lge/mdm/LGMDMManagerInternal;->checkMicPackageInWhitelist(Ljava/lang/String;)Z': 'return true;',
}

# Fields LG's libraries only read by reflection (none found yet), as in ../xiaomi-standins/known.py.
REFLECTED = {}

# LG framework classes and the Android (or LG) class each one extends, when the app's own use doesn't show it.
SUPERS = {
    # LG's permission guide UI provider: the app's subclass is passed as a GuideUiProvider.
    'Lcom/lge/app/permission/DefaultUiProvider;': 'Lcom/lge/app/permission/GuideUiProvider;',
    # LG's blur engine views: plain frames (no blur) off LG's ROM.
    'Lcom/lge/blurengine2/LGBlurFrameLayout;': 'Landroid/widget/FrameLayout;',
    # LG's "back to top" button layout.
    'Landroid/widget/TopButtonLayout;': 'Landroid/widget/FrameLayout;',
    'Lcom/lge/blurengine2/LGBlurBackgroundView;': 'Landroid/view/View;',
    # A plain value object (its use as a thrown type is a false positive of Missing.java).
    'Lcom/lge/blurengine2/LGBlurMetadata;': 'Ljava/lang/Object;',
    # LG's dynamic action bar library (a fork of the design library's collapsing app bar), named in layouts.
    'Lcom/lge/lgdynamicactionbar/CoordinatorLayout;': 'Landroid/widget/FrameLayout;',
    'Lcom/lge/lgdynamicactionbar/AppBarLayout;': 'Landroid/widget/LinearLayout;',
    'Lcom/lge/lgdynamicactionbar/CollapsingToolbarLayout;': 'Landroid/widget/FrameLayout;',
    # LG's SUI widget library (colour, date and time pickers), named in layouts.
    'Lcom/lge/sui/widget/control/SUIColorPickerRuby;': 'Landroid/widget/LinearLayout;',
    'Lcom/lge/sui/widget/control/SUIHueSeekBarRuby;': 'Landroid/widget/SeekBar;',
    'Lcom/lge/sui/widget/control/SUIHueSeekBarFakeRuby;': 'Landroid/view/View;',
    'Lcom/lge/sui/widget/control/SUISaturationBrightnessPickerViewRuby;': 'Landroid/view/View;',
}
