package org.ungoogled.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Uses Maps' own signed-out details service, not a web scraper or a Places API client. */
public final class NativePlaces {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<Object> loader = new WeakReference<>(null);
    private static volatile WeakReference<Object> provider = new WeakReference<>(null);

    private NativePlaces() {}

    public static void bind(Object instance) {
        loader = new WeakReference<>(instance);
    }

    public static void bindProvider(Object instance) {
        provider = new WeakReference<>(instance);
    }

    static boolean available() {
        return loader.get() != null || provider.get() != null;
    }

    static SavedStore.Place lookup(Context context, String featureId) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Place details must be awaited off the main thread");
        }
        CompletableFuture<Future<?>> started = new CompletableFuture<>();
        Runnable begin = () -> {
            if (started.isCancelled()) return;
            try {
                Object service = loader.get();
                Object supplier = provider.get();
                if (service == null && supplier != null) service = resolve(supplier);
                if (service == null) throw new ServiceUnavailable();
                Object request = request(service, featureId);
                if (!(request instanceof Future<?>)) {
                    throw new IllegalStateException("Maps returned an unsupported details request");
                }
                if (!started.complete((Future<?>) request)) ((Future<?>) request).cancel(true);
            } catch (RuntimeException | LinkageError e) {
                started.completeExceptionally(e);
            }
        };
        MAIN.post(begin);
        Future<?> request;
        try { request = started.get(10, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException | InterruptedException e) {
            started.cancel(false);
            MAIN.removeCallbacks(begin);
            throw e;
        }
        try {
            return details(request.get(45, TimeUnit.SECONDS), context);
        } finally {
            if (!request.isDone()) request.cancel(true);
        }
    }

    static Object resolve(Object provider) {
        throw new IllegalStateException("Native place service provider is not patched");
    }

    static final class ServiceUnavailable extends IllegalStateException {
        ServiceUnavailable() { super("Waiting for Maps' place service"); }
    }

    static final class UnsupportedPlaceId extends IllegalArgumentException {
        UnsupportedPlaceId() { super("This Place-ID variant has no verified native feature mapping. Original identifier retained."); }
    }

    /** Only the complete fixed64 feature envelope is reversible; other opaque Place-ID variants stay opaque. */
    static String featureFromPlaceId(String placeId) {
        if (placeId == null) return null;
        try {
            byte[] bytes = java.util.Base64.getUrlDecoder().decode(placeId);
            if (!java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(placeId) ||
                    bytes.length != 20 || bytes[0] != 10 || bytes[1] != 18 || bytes[2] != 9 || bytes[11] != 17) return null;
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            long high = buffer.getLong(3), low = buffer.getLong(12);
            if (high == 0 || low == 0) return null;
            return "0x" + Long.toHexString(high) + ":0x" + Long.toHexString(low);
        } catch (IllegalArgumentException e) { return null; }
    }

    static Object request(Object service, String featureId) {
        throw new IllegalStateException("Native place request bridge is not patched");
    }

    static SavedStore.Place details(Object place, Context context) {
        throw new IllegalStateException("Native place details bridge is not patched");
    }

    static String hours(Object place, Context context) {
        throw new IllegalStateException("Native place hours bridge is not patched");
    }

    public static Object capture(String name, Object featureId, Object position, String category,
            Object photos, String photoField, float rating, int reviews, String hours) {
        SavedStore.Place place = SavedPlaces.place(name, featureId, position);
        if (place == null || !place.located() || name == null || name.trim().isEmpty()) {
            throw new IllegalStateException("Maps returned incomplete place details");
        }
        place.category = category == null ? "" : category;
        place.photos.addAll(SavedPlaces.photoUrls(photos, photoField));
        place.rating = rating > 0 && rating <= 5 ? rating : Float.NaN;
        place.reviews = Math.max(0, reviews);
        place.hours = hours == null ? "" : hours.trim();
        return place;
    }
}
