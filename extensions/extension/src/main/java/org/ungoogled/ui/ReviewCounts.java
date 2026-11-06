package org.ungoogled.ui;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Review counts in navigation's search results (issue #123). Off navigation a result reads "4.2 (1,600)"; navigation
 * turns each place into a result of its own that keeps the rating but not the count. The count is kept here, by the
 * place's feature id, when the place is turned into a result, and put back after the rating wherever navigation
 * shows one: the results' callouts on the map, the card a tapped result opens, the results list.
 */
public final class ReviewCounts {
    /** plurals/REVIEW_COUNT_SHORT, Maps' own "(%,d)" (resource names do not survive a merged APK). */
    private static final int REVIEW_COUNT_SHORT = 0x7f1200eb;
    private static final Map<Object, Integer> COUNTS = new LinkedHashMap<Object, Integer>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Object, Integer> eldest) {
            return size() > 400;
        }
    };
    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Map<String, Field> FIELDS = new HashMap<>();

    private ReviewCounts() {}

    /** Rewritten by the patch: the obfuscated names of what is read and written here. */
    static String placeIdMethod() { return ""; }
    static String placeCountMethod() { return ""; }
    static String resultIdMethod() { return ""; }
    static String resultRatingMethod() { return ""; }
    static String fragmentMethod() { return ""; }
    static String addMethod() { return ""; }
    static String copyMethod() { return ""; }
    static String instanceField() { return ""; }
    static String textField() { return ""; }
    static String bitsField() { return ""; }
    static String ratingStyleField() { return ""; }
    static String countStyleField() { return ""; }

    /** A place on its way to becoming a navigation search result. */
    public static void keep(Object place) {
        try {
            if (place == null) return;
            Object id = method(place.getClass(), placeIdMethod()).invoke(place);
            int count = (Integer) method(place.getClass(), placeCountMethod()).invoke(place);
            if (id == null || count <= 0) return;
            synchronized (COUNTS) {
                COUNTS.put(id, count);
            }
        } catch (Throwable t) {
            warn(t);
        }
    }

    /** The results list's details line: the rating, followed by the review count. */
    public static CharSequence rating(CharSequence rating, Object result) {
        String count = rating == null || rating.length() == 0 ? null : count(result);
        return count == null ? rating : new SpannableStringBuilder(rating).append(' ').append(count);
    }

    /** A tapped result's card: its line is the rating ("4.2" and stars) only for a rated result. */
    public static CharSequence card(CharSequence line, Object result) {
        try {
            if (line == null || result == null) return line;
            Object rating = method(result.getClass(), resultRatingMethod()).invoke(result);
            if (!(rating instanceof Float) || !line.toString().startsWith(String.format(Locale.getDefault(), "%.1f", rating))) return line;
            return rating(line, result);
        } catch (Throwable t) {
            warn(t);
            return line;
        }
    }

    /**
     * A result's callout on the map, its rating and stars added: the review count as one more fragment, in the
     * detour line's style. Only a callout in the rating style shows a rating (others show prices or closing times);
     * right-to-left callouts put the stars first and are left as they are.
     */
    public static void callout(Object label, Object fragments, Object styles, Object style, Object result) {
        try {
            if (label == null || fragments == null || styles == null || style == null) return;
            if (style != field(styles.getClass(), ratingStyleField()).get(styles)) return;
            if (TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL) return;
            String count = count(result);
            if (count == null) return;
            Object countStyle = field(styles.getClass(), countStyleField()).get(styles);
            Object use = countStyle != null ? countStyle : style;
            Object fragment = method(fragments.getClass(), fragmentMethod(), use).invoke(fragments, use);
            method(fragment.getClass(), copyMethod()).invoke(fragment);
            Object message = field(fragment.getClass(), instanceField()).get(fragment);
            Field bits = field(message.getClass(), bitsField());
            bits.setInt(message, bits.getInt(message) | 1);
            field(message.getClass(), textField()).set(message, " " + count);
            method(label.getClass(), addMethod(), fragment).invoke(label, fragment);
        } catch (Throwable t) {
            warn(t);
        }
    }

    /** "(1,600)" for a result whose place had reviews, else null. */
    private static String count(Object result) {
        try {
            if (result == null) return null;
            Object id = method(result.getClass(), resultIdMethod()).invoke(result);
            Integer n;
            synchronized (COUNTS) {
                n = id == null ? null : COUNTS.get(id);
            }
            Context c = Shapes.appContext();
            if (n == null || c == null) return null;
            try {
                return c.getResources().getQuantityString(REVIEW_COUNT_SHORT, n, n);
            } catch (RuntimeException e) {
                return String.format(Locale.getDefault(), "(%,d)", n);
            }
        } catch (Throwable t) {
            warn(t);
            return null;
        }
    }

    /** The class's (or a superclass's) method of that name that takes these arguments. */
    private static Method method(Class<?> c, String name, Object... args) throws NoSuchMethodException {
        StringBuilder key = new StringBuilder(c.getName()).append('.').append(name);
        for (Object a : args) key.append(',').append(a == null ? "null" : a.getClass().getName());
        synchronized (METHODS) {
            Method known = METHODS.get(key.toString());
            if (known != null) return known;
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            next:
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] types = m.getParameterTypes();
                if (!m.getName().equals(name) || types.length != args.length) continue;
                for (int i = 0; i < types.length; i++) {
                    if (types[i].isPrimitive() || (args[i] != null && !types[i].isInstance(args[i]))) continue next;
                }
                m.setAccessible(true);
                synchronized (METHODS) {
                    METHODS.put(key.toString(), m);
                }
                return m;
            }
        }
        throw new NoSuchMethodException(key.toString());
    }

    private static boolean warned;

    /** Once: a Maps build these names do not fit should not fill the log. */
    private static void warn(Throwable t) {
        if (warned) return;
        warned = true;
        android.util.Log.w("UA", "review counts", t);
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        String key = c.getName() + '.' + name;
        synchronized (FIELDS) {
            Field known = FIELDS.get(key);
            if (known != null) return known;
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                synchronized (FIELDS) {
                    FIELDS.put(key, f);
                }
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(key);
    }
}
