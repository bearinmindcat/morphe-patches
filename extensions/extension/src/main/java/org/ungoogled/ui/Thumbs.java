package org.ungoogled.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Native photo URLs only; imports retain their supplied URLs as inert source data. */
final class Thumbs {
    static final int SIZE = 384;
    private static final long DISK_LIMIT = 256L * 1024 * 1024;
    private static final int DOWNLOAD_LIMIT = 2 * 1024 * 1024;
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), r -> new Thread(r, "UA-thumb"));
    private static final Map<String, Job> JOBS = new HashMap<>();
    private static final Object DISK = new Object();
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };

    private Thumbs() {}

    static void loadInto(ImageView view, String url, int px, Runnable shown) {
        cancel(view);
        String sized = sized(url, SIZE);
        if (sized == null) {
            if (url != null && !url.isEmpty()) android.util.Log.w("UA", "Unsupported thumbnail URL; placeholder retained");
            return;
        }
        Binding binding = new Binding(view, sized, shown);
        view.setTag(binding);
        view.addOnAttachStateChangeListener(binding);
        if (view.isAttachedToWindow()) binding.onViewAttachedToWindow(view);
    }

    static void cancel(ImageView view) {
        Object tag = view.getTag();
        if (tag instanceof Binding) {
            Binding binding = (Binding) tag;
            view.removeOnAttachStateChangeListener(binding);
            binding.onViewDetachedFromWindow(view);
        }
        view.setTag(null);
    }

    static boolean cached(Context c, String url) {
        String sized = sized(url, SIZE);
        if (sized == null) return false;
        File cached = file(c, sized);
        return cached.length() > 0 || new File(cached + ".bak").length() > 0;
    }

    static void prefetch(Context c, String url) throws Exception {
        String sized = sized(url, SIZE);
        if (sized == null) throw new IOException("No supported keyless thumbnail URL");
        Job job;
        synchronized (JOBS) {
            job = acquire(c, sized);
            job.prefetches++;
        }
        try {
            job.result.get(60, TimeUnit.SECONDS);
            if (!cached(c, url)) throw new IOException("Thumbnail bytes were not saved");
        } finally {
            synchronized (JOBS) { job.prefetches--; release(job); }
        }
    }

    private static final class Binding implements View.OnAttachStateChangeListener {
        final ImageView view;
        final String url;
        final Runnable shown;
        Job job;

        Binding(ImageView view, String url, Runnable shown) { this.view = view; this.url = url; this.shown = shown; }

        @Override public void onViewAttachedToWindow(View ignored) {
            if (job != null) return;
            Bitmap hit = MEMORY.get(url);
            if (hit != null) { show(hit); return; }
            synchronized (JOBS) {
                job = acquire(view.getContext(), url);
                job.bindings.add(this);
            }
        }

        @Override public void onViewDetachedFromWindow(View ignored) {
            synchronized (JOBS) {
                if (job == null) return;
                job.bindings.remove(this);
                release(job);
                job = null;
            }
        }

        void show(Bitmap bitmap) {
            if (view.getTag() != this || !view.isAttachedToWindow()) return;
            shown.run();
            view.setImageBitmap(bitmap);
        }
    }

    private static final class Job {
        final String url;
        final CompletableFuture<Bitmap> result = new CompletableFuture<>();
        final List<Binding> bindings = new ArrayList<>();
        Future<?> task;
        int prefetches;
        boolean started;

        Job(String url) { this.url = url; }
    }

    private static Job acquire(Context c, String url) {
        Job old = JOBS.get(url);
        if (old != null) return old;
        Job job = new Job(url);
        JOBS.put(url, job);
        Context app = c.getApplicationContext();
        try {
            job.task = POOL.submit(() -> {
                synchronized (JOBS) { job.started = true; }
                try {
                    Bitmap bitmap = fetch(app, url);
                    List<Binding> bindings;
                    synchronized (JOBS) {
                        bindings = new ArrayList<>(job.bindings);
                        if (!bindings.isEmpty()) MEMORY.put(url, bitmap);
                    }
                    job.result.complete(bitmap);
                    for (Binding binding : bindings) binding.view.post(() -> binding.show(bitmap));
                } catch (IOException | RuntimeException e) {
                    job.result.completeExceptionally(e);
                    android.util.Log.w("UA", "Representative thumbnail could not be cached: " + e.getClass().getSimpleName());
                } finally {
                    synchronized (JOBS) { JOBS.remove(url, job); job.bindings.clear(); }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            JOBS.remove(url, job);
            job.result.completeExceptionally(new IOException("Thumbnail queue is busy; retry", e));
            android.util.Log.w("UA", "Thumbnail queue is full");
        }
        return job;
    }

    private static void release(Job job) {
        if (!job.started && job.prefetches == 0 && job.bindings.isEmpty() && job.task != null) {
            job.task.cancel(false);
            POOL.remove((Runnable) job.task);
            JOBS.remove(job.url, job);
            job.result.cancel(false);
        }
    }

    static String sized(String url, int px) {
        if (url == null || url.isEmpty()) return null;
        String value = url.startsWith("//") ? "https:" + url : url;
        try {
            URL parsed = new URL(value);
            if (!"https".equals(parsed.getProtocol()) || !imageHost(parsed.getHost()) || parsed.getUserInfo() != null ||
                    parsed.getPort() != -1 && parsed.getPort() != 443) return null;
            value = parsed.toExternalForm();
            for (String parameter : android.net.Uri.parse(value).getQueryParameterNames()) {
                if (parameter.equalsIgnoreCase("key") || parameter.equalsIgnoreCase("api_key") || parameter.equalsIgnoreCase("apikey") ||
                        parameter.equalsIgnoreCase("x-goog-api-key")) return null;
            }
            if (parsed.getQuery() == null && parsed.getHost().endsWith(".googleusercontent.com")) {
                int eq = value.lastIndexOf('='), slash = value.lastIndexOf('/');
                if (eq > slash) value = value.substring(0, eq);
                value += "=w" + px + "-h" + px + "-k-no-p";
            }
            return value;
        } catch (java.net.MalformedURLException e) {
            return null;
        }
    }

    private static boolean imageHost(String host) {
        return host.endsWith(".googleusercontent.com") || host.endsWith(".ggpht.com")
                || host.equals("streetviewpixels-pa.googleapis.com") || host.equals("maps.googleapis.com")
                || host.equals("maps.google.com");
    }

    private static File file(Context c, String url) {
        return new File(new File(c.getCacheDir(), "ungoogled_thumbs"), hash(url) + ".jpg");
    }

    private static Bitmap fetch(Context c, String url) throws IOException {
        File file = file(c, url);
        synchronized (DISK) {
            if (file.exists() || new File(file + ".bak").exists()) {
                byte[] data = new AtomicFile(file).readFully();
                try { return decode(data); }
                catch (IOException e) {
                    if (!file.delete()) throw new IOException("Cannot replace damaged thumbnail", e);
                }
            }
        }
        URL current = new URL(url);
        byte[] data = null;
        for (int redirect = 0; redirect < 4; redirect++) {
            if (sized(current.toString(), SIZE) == null)
                throw new IOException("Unsupported thumbnail redirect");
            HttpURLConnection connection = LibraryNetwork.open(current);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Thumbnail redirect has no destination");
                    current = new URL(current, location);
                    continue;
                }
                if (status != 200) throw new IOException("Thumbnail HTTP " + status);
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    for (int n; (n = in.read(buffer)) != -1; ) {
                        if (out.size() + n > DOWNLOAD_LIMIT) throw new IOException("Thumbnail exceeds 2 MiB");
                        out.write(buffer, 0, n);
                    }
                    data = out.toByteArray();
                }
                break;
            } finally { connection.disconnect(); }
        }
        if (data == null) throw new IOException("Too many thumbnail redirects");
        Bitmap bitmap = decode(data);
        synchronized (DISK) {
            File dir = file.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("Cannot create thumbnail cache");
            long bytes = data.length;
            File[] files = dir.listFiles();
            if (files == null) throw new IOException("Cannot inspect thumbnail cache");
            for (File entry : files) bytes += entry.length();
            if (bytes > DISK_LIMIT) throw new IOException("Thumbnail cache is full (256 MiB)");
            AtomicFile atomic = new AtomicFile(file);
            FileOutputStream out = null;
            try {
                out = atomic.startWrite();
                out.write(data);
                atomic.finishWrite(out);
            } catch (IOException e) {
                if (out != null) atomic.failWrite(out);
                throw e;
            }
        }
        return bitmap;
    }

    private static Bitmap decode(byte[] data) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth < 1 || options.outHeight < 1 || (long) options.outWidth * options.outHeight > 16000000)
            throw new IOException("Invalid or oversized thumbnail");
        int sample = 1;
        while (options.outWidth / sample > SIZE * 2 || options.outHeight / sample > SIZE * 2) sample *= 2;
        options.inJustDecodeBounds = false;
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("Thumbnail could not be decoded");
        return bitmap;
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
