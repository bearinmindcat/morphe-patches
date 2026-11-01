package org.ungoogled.lgquickmemo;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.Display;
import android.view.DisplayCutout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;

/** What LG QuickMemo+ got from LG's ROM as a preinstalled app, answered on any phone (see the patch). */
public final class Compat {
    private Compat() {
    }

    /**
     * Context.getDisplay(): Android 11+ refuses it for a context without a display (the application's, a service's)
     * once an app targets Android 11, which QuickMemo+ does; LG's ROM answered the phone's screen. So does this.
     */
    public static Display getDisplay(Context context) {
        try {
            return context.getDisplay();
        } catch (UnsupportedOperationException e) {
            DisplayManager displays = context.getSystemService(DisplayManager.class);
            return displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        }
    }

    /**
     * DeviceInfoUtils.getNotchHeight: the cutout's inset on the screen edge it sits on, as before, from Android's public
     * Display.getCutout() (the app read the hidden DisplayInfo, which Android refuses an installed app).
     */
    public static int notchHeight(Display display) {
        if (display == null) return 0;
        DisplayCutout cutout = display.getCutout();
        if (cutout == null) return 0;
        switch (display.getRotation()) {
            case 0: return cutout.getSafeInsetTop();
            case 1: return cutout.getSafeInsetLeft();
            case 2: return cutout.getSafeInsetBottom();
            case 3: return cutout.getSafeInsetRight();
            default: return 0;
        }
    }

    /**
     * AdvancedLinkify.gatherTelLinks: phone numbers in a memo become "tel:" links, found by Android's public
     * Linkify (the app called the platform's hidden copy of libphonenumber, which Android refuses an installed app).
     * Adds the app's own LinkSpec objects (url, start, end) to its list.
     */
    @SuppressWarnings("unchecked")
    public static void gatherTelLinks(ArrayList list, Spannable text) {
        if (list == null || text == null) return;
        SpannableString copy = new SpannableString(text.toString());
        if (!Linkify.addLinks(copy, Linkify.PHONE_NUMBERS)) return;
        try {
            Class<?> spec = Class.forName("com.lge.qmemoplus.utils.LinkSpec");
            Constructor<?> make = spec.getDeclaredConstructor();
            make.setAccessible(true);
            Field url = spec.getDeclaredField("url");
            Field start = spec.getDeclaredField("start");
            Field end = spec.getDeclaredField("end");
            url.setAccessible(true);
            start.setAccessible(true);
            end.setAccessible(true);
            for (URLSpan span : copy.getSpans(0, copy.length(), URLSpan.class)) {
                Object link = make.newInstance();
                url.set(link, span.getURL());
                start.setInt(link, copy.getSpanStart(span));
                end.setInt(link, copy.getSpanEnd(span));
                list.add(link);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No phone links.
        }
    }

    /** startActivityAsUser(intent, options, CURRENT) of a system app: the same start, as the app itself. */
    public static void startActivityAsUser(Context context, android.content.Intent intent, android.os.Bundle options, android.os.UserHandle user) {
        context.startActivity(intent, options);
    }

    /**
     * StatusBarManager.disable (QuickMemo+'s "navigation bar lock" while drawing, and quick mode): a system app's
     * privilege (STATUS_BAR). Off LG's ROM nothing is locked.
     */
    public static void disable(android.app.StatusBarManager manager, int what) {
        try {
            manager.getClass().getMethod("disable", int.class).invoke(manager, what);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not allowed for an installed app (SecurityException inside the invocation).
        }
    }

    /** StatusBarManager.collapsePanels: refused without EXPAND_STATUS_BAR; then nothing. */
    public static void collapsePanels(android.app.StatusBarManager manager) {
        try {
            manager.getClass().getMethod("collapsePanels").invoke(manager);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not allowed.
        }
    }

    /**
     * LG's Privacy Lock (a separate LG app, com.lge.privacylock, that holds the lock for locked memos): off LG's ROM
     * the phone's own screen lock stands in. A lock "exists" when the phone has a secure screen lock.
     */
    public static boolean existLock(Context context) {
        android.app.KeyguardManager keyguard = context.getSystemService(android.app.KeyguardManager.class);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    /**
     * PrivacyLock's request to set a lock (type 1) or confirm it (type 2), answered with requestCode as LG's app
     * would: the phone's PIN / pattern / password / fingerprint confirmation (RESULT_OK when confirmed); without a
     * screen lock, Android's security settings to set one (the caller gets RESULT_CANCELED and asks again later).
     */
    public static boolean privacyLock(Object caller, int type, int requestCode) {
        android.app.Activity activity = caller instanceof android.app.Activity ? (android.app.Activity) caller
                : caller instanceof android.app.Fragment ? ((android.app.Fragment) caller).getActivity() : null;
        if (activity == null) {
            try {
                activity = (android.app.Activity) caller.getClass().getMethod("getActivity").invoke(caller);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }
        if (activity == null) return false;
        android.app.KeyguardManager keyguard = activity.getSystemService(android.app.KeyguardManager.class);
        android.content.Intent intent = null;
        if (keyguard != null && keyguard.isDeviceSecure()) {
            intent = keyguard.createConfirmDeviceCredentialIntent(null, null);
        } else {
            android.widget.Toast.makeText(activity, "Set a screen lock to lock memos", android.widget.Toast.LENGTH_LONG).show();
            intent = new android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
        }
        if (intent == null) return false;
        try {
            if (caller instanceof android.app.Activity) ((android.app.Activity) caller).startActivityForResult(intent, requestCode);
            else if (caller instanceof android.app.Fragment) ((android.app.Fragment) caller).startActivityForResult(intent, requestCode);
            else caller.getClass().getMethod("startActivityForResult", android.content.Intent.class, int.class).invoke(caller, intent, requestCode);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * LG's SearchView has a back button at its start (id searchBackButton), which the app shows and makes close the
     * search; Android's has none. An up arrow (the theme's homeAsUpIndicator) put first in the search bar stands in;
     * SearchView never changes its visibility. The same button again for a SearchView that already has one.
     */
    public static android.view.View searchBackButton(android.view.View searchView, int lgId) {
        android.view.View existing = searchView.findViewWithTag("lg_search_back");
        if (existing != null) return existing;
        Context context = searchView.getContext();
        android.widget.ImageView button = new android.widget.ImageView(context);
        button.setTag("lg_search_back");
        android.content.res.TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.homeAsUpIndicator, android.R.attr.selectableItemBackgroundBorderless});
        button.setImageDrawable(a.getDrawable(0));
        button.setBackground(a.getDrawable(1));
        a.recycle();
        int up = context.getResources().getIdentifier("action_bar_up_description", "string", "android");
        button.setContentDescription(up != 0 ? context.getString(up) : "Navigate up");
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        int size = Math.round(48 * context.getResources().getDisplayMetrics().density);
        int bar = context.getResources().getIdentifier("search_bar", "id", "android");
        android.view.View parent = bar != 0 ? searchView.findViewById(bar) : null;
        android.view.ViewGroup group = parent instanceof android.view.ViewGroup ? (android.view.ViewGroup) parent
                : searchView instanceof android.view.ViewGroup ? (android.view.ViewGroup) searchView : null;
        if (group != null) {
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(size, size);
            params.gravity = android.view.Gravity.CENTER_VERTICAL;
            group.addView(button, 0, params);
        }
        return button;
    }
}
