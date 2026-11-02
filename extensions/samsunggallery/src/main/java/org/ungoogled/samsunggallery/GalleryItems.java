package org.ungoogled.samsunggallery;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads Gallery's own objects (its file items, trash helpers and their loggers), whose class and member names
 * Gallery keeps, by reflection: the extension is built without Gallery's classes.
 */
final class GalleryItems {
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Object NONE = new Object();
    private static final ConcurrentHashMap<String, Object> FIELDS = new ConcurrentHashMap<>();

    private GalleryItems() {
    }

    private static Method method(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        String key = type.getName() + "#" + name + "/" + params.length;
        Method m = METHODS.get(key);
        if (m == null) {
            m = type.getMethod(name, params);
            m.setAccessible(true);
            METHODS.put(key, m);
        }
        return m;
    }

    static Object call(Object target, String name) {
        try {
            return method(target.getClass(), name).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(MediaRequests.TAG, "Gallery " + target.getClass().getName() + "." + name + "(): " + e);
            return null;
        }
    }

    /** The MediaStore id of a file item: Gallery's file id is MediaStore's _id off One UI (see MediaMirror). */
    static long id(Object item) {
        Object id = call(item, "getFileId");
        long value = id instanceof Long ? (Long) id : -1;
        if (value <= 0) {
            Object media = call(item, "getMediaId");
            if (media instanceof Long) value = (Long) media;
        }
        return value;
    }

    static boolean video(Object item) {
        return Boolean.TRUE.equals(call(item, "isVideo"));
    }

    static String path(Object item) {
        Object path = call(item, "getPath");
        return path instanceof String ? (String) path : null;
    }

    static boolean flag(Object target, String name) {
        Field f = field(target.getClass(), name);
        try {
            return f != null && f.getBoolean(target);
        } catch (IllegalAccessException | RuntimeException e) {
            return false;
        }
    }

    private static Field field(Class<?> type, String name) {
        String key = type.getName() + "#" + name;
        Object cached = FIELDS.get(key);
        if (cached == null) {
            cached = NONE;
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    cached = f;
                    break;
                } catch (NoSuchFieldException ignored) {
                }
            }
            FIELDS.put(key, cached);
        }
        return cached instanceof Field ? (Field) cached : null;
    }

    /** An enum constant's own enum (a constant with a body is a subclass of it). */
    private static Class<?> type(Object value) {
        return value instanceof Enum ? ((Enum<?>) value).getDeclaringClass() : value.getClass();
    }

    /** One of a trash helper's logger calls (started, succeed, failed) for an item. */
    static void log(Object helper, String what, Object item) {
        Object logger = call(helper, "getLogger");
        if (logger == null) return;
        Object mediaType = call(item, "getMediaType");
        Object storageType = call(item, "getStorageType");
        if (mediaType == null || storageType == null) return;
        try {
            method(logger.getClass(), what, type(mediaType), type(storageType)).invoke(logger, mediaType, storageType);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(MediaRequests.TAG, "Gallery trash log " + what + ": " + e);
        }
    }
}
