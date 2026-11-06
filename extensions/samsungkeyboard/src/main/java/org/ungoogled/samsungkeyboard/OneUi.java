package org.ungoogled.samsungkeyboard;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.IBinder;
import android.content.res.Resources;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What One UI adds to Android's own classes (methods and fields named sem..., SEM_...), used where the phone has
 * it and answered as "not there" where it does not: a skipped call, 0 / false / null, or for a call that returns
 * its own object (a builder) that object. The patch sends every such call and field access in the app here, through
 * a small generated method per member; the key names the member as dex does: "Lowner;->name(params)return" or
 * "Lowner;->name:type".
 */
public final class OneUi {
    private static final String TAG = "UA";
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<String, Object> MEMBERS = new ConcurrentHashMap<>();

    private OneUi() {
    }

    private static Class<?> type(String descriptor) throws ClassNotFoundException {
        switch (descriptor) {
            case "V": return void.class;
            case "Z": return boolean.class;
            case "B": return byte.class;
            case "S": return short.class;
            case "C": return char.class;
            case "I": return int.class;
            case "J": return long.class;
            case "F": return float.class;
            case "D": return double.class;
        }
        String name = descriptor.startsWith("[") ? descriptor.replace('/', '.')
                : descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        return Class.forName(name, false, OneUi.class.getClassLoader());
    }

    private static List<String> parameters(String list) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < list.length()) {
            int start = i;
            while (list.charAt(i) == '[') i++;
            if (list.charAt(i) == 'L') i = list.indexOf(';', i);
            i++;
            out.add(list.substring(start, i));
        }
        return out;
    }

    /** The member a key names, or MISSING where this phone does not have it. */
    private static Object member(String key) {
        Object found = MEMBERS.get(key);
        if (found != null) return found;
        try {
            int arrow = key.indexOf("->");
            Class<?> owner = type(key.substring(0, arrow));
            String rest = key.substring(arrow + 2);
            int paren = rest.indexOf('(');
            if (paren < 0) {
                Field field = owner.getField(rest.substring(0, rest.indexOf(':')));
                field.setAccessible(true);
                found = field;
            } else {
                List<String> list = parameters(rest.substring(paren + 1, rest.indexOf(')')));
                Class<?>[] types = new Class<?>[list.size()];
                for (int i = 0; i < types.length; i++) types[i] = type(list.get(i));
                Method method = owner.getMethod(rest.substring(0, paren), types);
                method.setAccessible(true);
                found = method;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            found = MISSING;
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "One UI member missing: " + key);
        }
        MEMBERS.put(key, found);
        return found;
    }

    /** What a missing member answers: nothing, zero, false, null, or for a builder-style call its own object. */
    private static Object absent(Object receiver, String key) {
        String type = key.substring(key.lastIndexOf(key.indexOf('(') < 0 ? ':' : ')') + 1);
        switch (type) {
            case "V": return null;
            case "Z": return Boolean.FALSE;
            case "B": return (byte) 0;
            case "S": return (short) 0;
            case "C": return (char) 0;
            case "I": return 0;
            case "J": return 0L;
            case "F": return 0f;
            case "D": return 0d;
        }
        try {
            if (receiver != null && type(type).isInstance(receiver)) return receiver;
        } catch (ReflectiveOperationException | LinkageError e) {
            // The return type itself is One UI's.
        }
        return null;
    }

    private static RuntimeException rethrow(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
        return new RuntimeException(cause);
    }

    public static Object call(Object receiver, String key, Object[] args) {
        Object m = member(key);
        if (m == MISSING) {
            Object answer = Fallbacks.call(receiver, key, args);
            return answer != Fallbacks.NONE ? answer : absent(receiver, key);
        }
        try {
            return ((Method) m).invoke(receiver, args);
        } catch (InvocationTargetException e) {
            throw rethrow(e);
        } catch (IllegalAccessException e) {
            return absent(receiver, key);
        }
    }

    /** A super call cannot be made from here; off One UI there is no super method to call anyway. */
    public static Object callSuper(Object receiver, String key, Object[] args) {
        return absent(receiver, key);
    }

    public static Object get(Object receiver, String key) {
        Object f = member(key);
        if (f == MISSING) {
            Object answer = Fallbacks.get(receiver, key);
            return answer != Fallbacks.NONE ? answer : absent(receiver, key);
        }
        try {
            return ((Field) f).get(receiver);
        } catch (IllegalAccessException e) {
            return absent(receiver, key);
        }
    }

    public static void set(Object receiver, String key, Object value) {
        Object f = member(key);
        if (f == MISSING) return;
        try {
            ((Field) f).set(receiver, value);
        } catch (IllegalAccessException e) {
            // Left as it is.
        }
    }

    /**
     * Resources.getIdentifier for the app (patches/.../samsung/OneUiFrameworkResources.kt sends its calls here): a
     * resource only One UI's framework has ("android" package) answers 0 off One UI, and the app uses some unchecked
     * (getColor(0) throws). Where the patch added a stand-in for it ("oneui_<name>" in the app) the lookup gets that;
     * everything else answers as Android does. Never for the system Resources, which cannot read the app's resources.
     */
    public static int getIdentifier(Resources resources, String name, String type, String pkg) {
        int id = resources.getIdentifier(name, type, pkg);
        if (id != 0 || name == null || resources == Resources.getSystem()) return id;
        String n = name, t = type, p = pkg;
        int colon = n.indexOf(':');
        if (colon >= 0) {
            p = n.substring(0, colon);
            n = n.substring(colon + 1);
        }
        int slash = n.indexOf('/');
        if (slash >= 0) {
            t = n.substring(0, slash);
            n = n.substring(slash + 1);
        }
        if (!"android".equals(p) || t == null) return 0;
        String app = Application.getProcessName();
        if (app == null) return 0;
        if (app.indexOf(':') >= 0) app = app.substring(0, app.indexOf(':'));
        return resources.getIdentifier("oneui_" + n, t, app);
    }

    private static volatile Boolean oneUi;

    /** Whether the phone runs One UI: its Build.VERSION carries SEM_PLATFORM_INT. */
    static boolean isOneUi() {
        Boolean known = oneUi;
        if (known == null) {
            boolean has;
            try {
                Build.VERSION.class.getField("SEM_PLATFORM_INT");
                has = true;
            } catch (NoSuchFieldException | RuntimeException e) {
                has = false;
            }
            oneUi = known = has;
        }
        return known;
    }

    private static volatile WeakReference<InputMethodService> keyboard = new WeakReference<>(null);

    /** Called as the keyboard's input method service starts (see the patch). */
    public static void keyboardCreated(InputMethodService service) {
        keyboard = new WeakReference<>(service);
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /**
     * The keyboard shows its dialogs (network notices, delete confirmations ...) as input method dialogs, a system window
     * type only a system app may add: an ordinary app's Dialog.show() died. Where the app may not, an input method
     * dialog becomes a dialog attached to the keyboard's own window, as other keyboards show theirs; a dialog of one of
     * its Activities keeps the Activity's own type. Returns whether the window was changed.
     */
    private static boolean attachToKeyboard(Window window, int type) {
        if (type != WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG) return false;
        Context context = window.getContext();
        if (context.checkSelfPermission("android.permission.INTERNAL_SYSTEM_WINDOW") == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (activityOf(context) != null) {
            attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION;
            window.setAttributes(attributes);
            return true;
        }
        InputMethodService service = keyboard.get();
        IBinder token = null;
        try {
            if (service != null && service.getWindow() != null && service.getWindow().getWindow() != null) {
                token = service.getWindow().getWindow().getDecorView().getWindowToken();
            }
        } catch (RuntimeException e) {
            token = null;
        }
        if (token == null) return false;
        attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        attributes.token = token;
        window.setAttributes(attributes);
        return true;
    }

    /** Window.setType for the app: see attachToKeyboard. */
    public static void setType(Window window, int type) {
        if (!attachToKeyboard(window, type)) window.setType(type);
    }

    /**
     * Window.setFlags for the app. Its settings pages lay their windows out with FLAG_LAYOUT_NO_LIMITS, and off One UI
     * Android then reports such a window no system bar insets: the pages' title bars sat under the status bar. Off
     * One UI an app window (not the keyboard's own) keeps Android's layout limits; everything else is unchanged. A
     * dialog typed as an input method dialog is attached to the keyboard here too (see attachToKeyboard).
     */
    public static void setFlags(Window window, int flags, int mask) {
        int noLimits = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if ((mask & noLimits) != 0 && !isOneUi()) {
            int type = window.getAttributes().type;
            if (type >= WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                    && type <= WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) {
                flags &= ~noLimits;
                mask &= ~noLimits;
            }
        }
        window.setFlags(flags, mask);
        // A dialog may also have been typed through its attributes (the delete confirmations).
        attachToKeyboard(window, window.getAttributes().type);
    }
}
