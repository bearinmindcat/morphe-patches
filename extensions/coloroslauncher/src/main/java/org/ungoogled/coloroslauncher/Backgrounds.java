package org.ungoogled.coloroslauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What the launcher draws behind the app drawer, folders and an app's menu. ColorOS draws a blurred copy of the
 * wallpaper there; an installed app may not read the wallpaper, so off ColorOS that copy is a grey of the wallpaper's
 * brightness (see Wallpapers). The launcher's Home screen settings get a choice (row "Background behind the drawer and
 * menus"): that grey; a real blur of the wallpaper through Android's window blur (Android 12+, where the phone has it
 * on); transparent (the wallpaper itself shows); or a colour with a transparency.
 */
public final class Backgrounds {
    static final String KEY = "ungoogled_menu_background";
    private static final String PREFS = "ungoogled_backgrounds";
    private static final int GREY = 0, REAL_BLUR = 1, CLEAR = 2, COLOUR = 3;
    private static final int[] COLOURS = { Color.BLACK, 0xff3c3c3c, Color.WHITE };
    private static final String[] COLOUR_NAMES = { "Black", "Grey", "White" };
    /** The window blur at full strength (pixels). */
    private static final int RADIUS = 100;
    /** How much of the grey stays over a real blur, so labels keep their contrast. */
    private static final int REAL_BLUR_TINT = 110;

    /** The drawer/folder blur views and the drawable the launcher gave each. */
    private static final Map<View, Drawable> STATIC_VIEWS = new WeakHashMap<>();
    private static final Map<Window, float[]> BLURS = new WeakHashMap<>();

    private Backgrounds() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether this phone draws window blurs now (Android 12+, the phone supports it and has it on). */
    static boolean realBlurAvailable(Context c) {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            WindowManager wm = c.getSystemService(WindowManager.class);
            return wm != null && wm.isCrossWindowBlurEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int mode(Context c) {
        int mode = prefs(c).getInt("mode", GREY);
        return mode == REAL_BLUR && !realBlurAvailable(c) ? GREY : mode;
    }

    private static int colour(Context c) {
        SharedPreferences p = prefs(c);
        int opacity = Math.max(0, Math.min(100, 100 - p.getInt("transparency", 30)));
        return (Math.round(opacity * 2.55f) << 24) | (p.getInt("colour", Color.BLACK) & 0xffffff);
    }

    /** The background for a blur layer in the chosen mode, from the drawable the launcher gave it. */
    private static Drawable replace(Context c, Drawable original) {
        switch (mode(c)) {
            case CLEAR:
                return null;
            case COLOUR:
                // The menu draws its layers at the bounds the launcher gave them (a view's background gets its own).
                Drawable colour = new ColorDrawable(colour(c));
                if (original != null) colour.setBounds(original.getBounds());
                return colour;
            case REAL_BLUR:
                if (original == null) return null;
                Drawable tint = original.getConstantState() != null ? original.getConstantState().newDrawable().mutate() : original;
                tint.setBounds(original.getBounds());
                tint.setAlpha(REAL_BLUR_TINT);
                return tint;
            default:
                return original;
        }
    }

    // --- the drawer / folder blur layer (OplusStaticBlurView) ---

    /** OplusStaticBlurView.setBackground(drawable). */
    public static void setStaticBackground(View view, Drawable drawable) {
        if (view == null) return;
        synchronized (STATIC_VIEWS) {
            STATIC_VIEWS.put(view, drawable);
        }
        view.setBackground(replace(view.getContext(), drawable));
    }

    /** 15.6.24's drawer tint over the blur and the tint the launcher gave it. */
    private static final Map<View, Drawable> TINT_VIEWS = new WeakHashMap<>();

    /** OplusAllAppsBg.setBackground(drawable) (15.6.24): kept over a blur, left out when transparent or a colour. */
    public static void setDrawerTint(View view, Drawable drawable) {
        if (view == null) return;
        synchronized (TINT_VIEWS) {
            TINT_VIEWS.put(view, drawable);
        }
        view.setBackground(tint(view.getContext(), drawable));
    }

    private static Drawable tint(Context c, Drawable original) {
        int mode = mode(c);
        return mode == CLEAR || mode == COLOUR ? null : original;
    }

    /** OplusStaticBlurView.setAlpha(alpha): the window blur follows the layer. */
    public static void staticAlpha(View view, float alpha) {
        blur(view, 0, alpha);
    }

    // --- an app's menu (PopupBlurView) ---

    /** PopupBlurView.setWallpaperDrawable(drawable): the wallpaper layer under the blurred icons. */
    public static Drawable popupWallpaper(View view, Drawable drawable) {
        return view == null ? drawable : replace(view.getContext(), drawable);
    }

    /**
     * PopupBlurView.setDragLayerDrawable(drawable): the home screen blurred, drawn over the wallpaper layer. Transparent
     * and Colour show the home screen itself under the menu, so it is left out there.
     */
    public static Drawable popupDragLayer(View view, Drawable drawable) {
        if (view == null) return drawable;
        int mode = mode(view.getContext());
        return mode == CLEAR || mode == COLOUR ? null : drawable;
    }

    /** PopupBlurView.createBlurAnim(open): the menu's background (the animator's target) fading in or out. */
    public static void popupAnimator(ObjectAnimator animator) {
        if (animator == null || !(animator.getTarget() instanceof View)) return;
        final View view = (View) animator.getTarget();
        // An update comes before the animator sets the alpha, so its value is read from the animator.
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                Object value = a.getAnimatedValue();
                popupAlpha(view, value instanceof Float ? (Float) value : view.getAlpha());
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                popupAlpha(view, view.getAlpha());
            }
        });
    }

    /**
     * The menu's background at its current alpha: the window blur follows it, and with Real blur the views under the
     * menu fade out as it fades in (the menu draws them blurred over a see-through tint, so they would show sharp).
     */
    private static void popupAlpha(View view, float alpha) {
        blur(view, 1, alpha);
        if (mode(view.getContext()) == REAL_BLUR) fadeBelow(view, 1f - alpha);
        else fadeBelow(view, 1f);
    }

    /** PopupBlurView.finish: the menu is gone. */
    public static void popupGone(View view) {
        blur(view, 1, 0f);
        fadeBelow(view, 1f);
    }

    /** Per menu: the views under it that were faded, each with {its own alpha, the alpha set here}. */
    private static final Map<View, Map<View, float[]>> FADED = new WeakHashMap<>();

    /** The menu's siblings under it at [factor] of their own alpha; at 1 they get their own alpha back. */
    private static void fadeBelow(View popup, float factor) {
        if (popup == null) return;
        synchronized (FADED) {
            Map<View, float[]> faded = FADED.get(popup);
            if (factor >= 1f) {
                if (faded == null) return;
                FADED.remove(popup);
                for (Map.Entry<View, float[]> e : faded.entrySet()) {
                    View child = e.getKey();
                    // Left alone if the launcher changed it meanwhile.
                    if (child != null && child.getAlpha() == e.getValue()[1]) child.setAlpha(e.getValue()[0]);
                }
                return;
            }
            if (!(popup.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) popup.getParent();
            if (faded == null) {
                faded = new WeakHashMap<>();
                FADED.put(popup, faded);
            }
            int index = parent.indexOfChild(popup);
            for (int i = 0; i < index; i++) {
                View child = parent.getChildAt(i);
                float[] alphas = faded.get(child);
                if (alphas == null) {
                    alphas = new float[] { child.getAlpha(), -1f };
                    faded.put(child, alphas);
                } else if (child.getAlpha() != alphas[1]) {
                    alphas[0] = child.getAlpha();
                }
                alphas[1] = alphas[0] * Math.max(0f, factor);
                child.setAlpha(alphas[1]);
            }
        }
    }

    /** Window blur behind the launcher, the strongest of its two sources (drawer/folders, menu). */
    private static void blur(View view, int source, float fraction) {
        if (view == null || Build.VERSION.SDK_INT < 31) return;
        Activity activity = activity(view.getContext());
        if (activity == null) return;
        Window window = activity.getWindow();
        float[] sources;
        synchronized (BLURS) {
            sources = BLURS.get(window);
            if (sources == null) {
                sources = new float[] { 0f, 0f, -1f };
                BLURS.put(window, sources);
            }
        }
        boolean real = mode(view.getContext()) == REAL_BLUR;
        sources[source] = real ? Math.max(0f, Math.min(1f, fraction)) : 0f;
        // Steps of 5 px: the window's attributes change at most a few times per animation.
        int radius = Math.round(Math.max(sources[0], sources[1]) * RADIUS / 5f) * 5;
        if (radius == sources[2]) return;
        sources[2] = radius;
        try {
            window.setBackgroundBlurRadius(radius);
        } catch (RuntimeException e) {
            // no blur then
        }
    }

    private static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    // --- the settings row ---

    /** LauncherSettingsFragment.onCreatePreferences: the row added to the Effects section opens the choice. */
    public static void attach(Object fragment) {
        try {
            Method find = fragment.getClass().getMethod("findPreference", CharSequence.class);
            final Object preference = find.invoke(fragment, KEY);
            if (preference == null) return;
            final Context c = (Context) preference.getClass().getMethod("getContext").invoke(preference);
            summary(preference, c);
            Class<?> listener = Class.forName("androidx.preference.Preference$OnPreferenceClickListener", false,
                    preference.getClass().getClassLoader());
            Object click = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] { listener }, (self, method, args) -> {
                switch (method.getName()) {
                    case "onPreferenceClick":
                        Activity a = activity(c);
                        if (a != null) choose(a, preference);
                        return Boolean.TRUE;
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "equals":
                        return args != null && args[0] == self;
                    default:
                        return null;
                }
            });
            Method set = null;
            for (Method m : preference.getClass().getMethods()) {
                if (m.getName().equals("setOnPreferenceClickListener") && m.getParameterTypes().length == 1) set = m;
            }
            if (set != null) set.invoke(preference, click);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // the row stays without a choice
        }
    }

    private static String name(Context c) {
        SharedPreferences p = prefs(c);
        switch (p.getInt("mode", GREY)) {
            case REAL_BLUR:
                return realBlurAvailable(c) ? "Real blur" : "Real blur (not on this phone: grey)";
            case CLEAR:
                return "Transparent";
            case COLOUR:
                int index = 0;
                for (int i = 0; i < COLOURS.length; i++) if (COLOURS[i] == p.getInt("colour", Color.BLACK)) index = i;
                return COLOUR_NAMES[index] + ", " + p.getInt("transparency", 30) + "% transparent";
            default:
                return "Blurred (grey)";
        }
    }

    private static void summary(Object preference, Context c) {
        try {
            preference.getClass().getMethod("setSummary", CharSequence.class).invoke(preference, name(c));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // no summary then
        }
    }

    private static void choose(Activity a, Object preference) {
        SharedPreferences p = prefs(a);
        boolean night = (a.getResources().getConfiguration().uiMode & 0x30) == 0x20;
        int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, a.getResources().getDisplayMetrics()));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        RadioGroup modes = new RadioGroup(a);
        String[] labels = { "Blurred (grey)", "Real blur", "Transparent", "Colour" };
        for (int i = 0; i < labels.length; i++) {
            RadioButton b = new RadioButton(a);
            b.setId(100 + i);
            b.setText(labels[i]);
            if (i == REAL_BLUR && !realBlurAvailable(a)) {
                b.setText("Real blur (not on this phone)");
                b.setEnabled(false);
            }
            modes.addView(b);
        }
        modes.check(100 + p.getInt("mode", GREY));
        box.addView(modes);

        RadioGroup colours = new RadioGroup(a);
        colours.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < COLOURS.length; i++) {
            RadioButton b = new RadioButton(a);
            b.setId(200 + i);
            b.setText(COLOUR_NAMES[i]);
            colours.addView(b);
            if (COLOURS[i] == p.getInt("colour", Color.BLACK)) colours.check(200 + i);
        }
        if (colours.getCheckedRadioButtonId() == -1) colours.check(200);
        TextView transparency = new TextView(a);
        SeekBar seek = new SeekBar(a);
        seek.setMax(90);
        seek.setProgress(p.getInt("transparency", 30));
        transparency.setText("Transparency: " + seek.getProgress() + "%");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                transparency.setText("Transparency: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        LinearLayout colourBox = new LinearLayout(a);
        colourBox.setOrientation(LinearLayout.VERTICAL);
        colourBox.setPadding(pad, 0, 0, 0);
        colourBox.addView(colours);
        colourBox.addView(transparency);
        colourBox.addView(seek);
        box.addView(colourBox);
        colourBox.setVisibility(modes.getCheckedRadioButtonId() == 100 + COLOUR ? View.VISIBLE : View.GONE);
        modes.setOnCheckedChangeListener((g, id) -> colourBox.setVisibility(id == 100 + COLOUR ? View.VISIBLE : View.GONE));

        AlertDialog dialog = new AlertDialog.Builder(a, night
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Background behind the drawer and menus")
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    p.edit()
                            .putInt("mode", Math.max(0, modes.getCheckedRadioButtonId() - 100))
                            .putInt("colour", COLOURS[Math.max(0, colours.getCheckedRadioButtonId() - 200)])
                            .putInt("transparency", seek.getProgress())
                            .apply();
                    summary(preference, a);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        Lock.accentButtons(a, dialog);
    }

    /** After a change: the blur layers already made take the new background. */
    private static void refresh() {
        synchronized (STATIC_VIEWS) {
            for (Map.Entry<View, Drawable> e : STATIC_VIEWS.entrySet()) {
                View v = e.getKey();
                if (v != null) v.setBackground(replace(v.getContext(), e.getValue()));
            }
        }
        synchronized (TINT_VIEWS) {
            for (Map.Entry<View, Drawable> e : TINT_VIEWS.entrySet()) {
                View v = e.getKey();
                if (v != null) v.setBackground(tint(v.getContext(), e.getValue()));
            }
        }
    }
}
