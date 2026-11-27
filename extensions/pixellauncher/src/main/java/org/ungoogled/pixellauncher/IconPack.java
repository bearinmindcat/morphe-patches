package org.ungoogled.pixellauncher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One installed icon pack in the format ADW, Nova, Lawnchair and most other launchers read: an appfilter.xml (in
 * res/xml or assets) mapping "ComponentInfo{package/activity}" to a drawable name, plus optional iconback / iconmask
 * / iconupon / scale entries for shaping the icons the pack doesn't draw, and calendar entries (a drawable per day).
 */
final class IconPack {
    private static final String TAG = "PixelIconPack";

    final String packageName;
    final long version;
    private final Resources res;

    /** "package/activity" -> drawable name. */
    private final Map<String, String> components = new HashMap<>();
    /** package -> the pack's first drawable for it, for activities the pack lists under another name. */
    private final Map<String, String> packages = new HashMap<>();
    /** "package/activity" -> drawable prefix; the day of the month (1-31) is appended. */
    private final Map<String, String> calendars = new HashMap<>();
    private final Map<String, String> calendarPackages = new HashMap<>();

    private final List<String> backs = new ArrayList<>();
    private String mask;
    private String upon;
    private float scale = 1f;

    private IconPack(String packageName, long version, Resources res) {
        this.packageName = packageName;
        this.version = version;
        this.res = res;
    }

    /** The pack in [packageName], or null when it is missing or has no readable appfilter.xml. */
    static IconPack load(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            long version = pm.getPackageInfo(packageName, 0).getLongVersionCode();
            Resources res = pm.getResourcesForApplication(packageName);
            IconPack pack = new IconPack(packageName, version, res);
            XmlPullParser parser = null;
            InputStream in = null;
            int id = res.getIdentifier("appfilter", "xml", packageName);
            if (id != 0) {
                parser = res.getXml(id);
            } else {
                try {
                    in = res.getAssets().open("appfilter.xml");
                    parser = Xml.newPullParser();
                    parser.setInput(in, null);
                } catch (Exception e) {
                    if (in != null) in.close();
                    in = null;
                }
            }
            if (parser == null) {
                Log.w(TAG, packageName + " has no appfilter.xml");
                return null;
            }
            try {
                pack.parse(parser);
            } finally {
                if (in != null) in.close();
            }
            pack.dropMissingShapes();
            Log.i(TAG, packageName + ": " + pack.components.size() + " icons" + (pack.shapes() ? ", shapes other icons" : ""));
            return pack;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "icon pack " + packageName + " isn't installed");
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "can't read icon pack " + packageName, t);
            return null;
        }
    }

    private void parse(XmlPullParser parser) throws Exception {
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if ("item".equals(tag)) {
                String key = componentKey(parser.getAttributeValue(null, "component"));
                String drawable = parser.getAttributeValue(null, "drawable");
                if (key == null || drawable == null || drawable.isEmpty()) continue;
                if (!components.containsKey(key)) components.put(key, drawable);
                String pkg = key.substring(0, key.indexOf('/'));
                if (!packages.containsKey(pkg)) packages.put(pkg, drawable);
            } else if ("calendar".equals(tag)) {
                String key = componentKey(parser.getAttributeValue(null, "component"));
                String prefix = parser.getAttributeValue(null, "prefix");
                if (key == null || prefix == null || prefix.isEmpty()) continue;
                calendars.put(key, prefix);
                String pkg = key.substring(0, key.indexOf('/'));
                if (!calendarPackages.containsKey(pkg)) calendarPackages.put(pkg, prefix);
            } else if ("iconback".equals(tag)) {
                for (int i = 0; i < parser.getAttributeCount(); i++) {
                    if (parser.getAttributeName(i).startsWith("img")) backs.add(parser.getAttributeValue(i));
                }
            } else if ("iconmask".equals(tag)) {
                mask = parser.getAttributeValue(null, "img1");
            } else if ("iconupon".equals(tag)) {
                upon = parser.getAttributeValue(null, "img1");
            } else if ("scale".equals(tag)) {
                try {
                    float f = Float.parseFloat(parser.getAttributeValue(null, "factor"));
                    if (f > 0f && f <= 2f) scale = f;
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** "ComponentInfo{pkg/.Activity}" -> "pkg/pkg.Activity"; null for anything else. */
    private static String componentKey(String component) {
        if (component == null) return null;
        String c = component.trim();
        if (c.startsWith("ComponentInfo{")) c = c.substring("ComponentInfo{".length());
        if (c.endsWith("}")) c = c.substring(0, c.length() - 1);
        int slash = c.indexOf('/');
        if (slash <= 0 || slash == c.length() - 1) return null;
        String pkg = c.substring(0, slash);
        String cls = c.substring(slash + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        return pkg + "/" + cls;
    }

    /** Forgets back/mask/upon images the pack names but doesn't ship (some list them empty-handed). */
    private void dropMissingShapes() {
        backs.removeIf(name -> id(name) == 0);
        if (mask != null && id(mask) == 0) mask = null;
        if (upon != null && id(upon) == 0) upon = null;
    }

    private int id(String name) {
        int id = res.getIdentifier(name, "drawable", packageName);
        return id != 0 ? id : res.getIdentifier(name, "mipmap", packageName);
    }

    /** Whether the pack shapes the icons it doesn't draw (an iconback, iconmask or iconupon it ships). */
    boolean shapes() {
        return !backs.isEmpty() || mask != null || upon != null;
    }

    /**
     * The pack's icon for [info]: its own drawing, or, with [shapeOthers], the app's icon shaped by the pack's
     * back/mask/upon images. Null when the pack has neither.
     */
    Drawable icon(Context context, PackageItemInfo info, ApplicationInfo appInfo, int dpi, boolean shapeOthers) {
        String pkg = info.packageName;
        String key = info.name != null && !info.name.isEmpty() ? pkg + "/" + info.name : null;
        String prefix = key != null ? calendars.get(key) : null;
        if (prefix == null) prefix = calendarPackages.get(pkg);
        if (prefix != null) {
            Drawable d = drawable(prefix + Calendar.getInstance().get(Calendar.DAY_OF_MONTH), dpi);
            if (d != null) return d;
        }
        String name = key != null ? components.get(key) : null;
        if (name == null) name = packages.get(pkg);
        if (name != null) {
            Drawable d = drawable(name, dpi);
            if (d != null) return d;
        }
        if (shapeOthers && shapes()) {
            return shaped(context, info, appInfo != null ? appInfo : info instanceof ApplicationInfo ? (ApplicationInfo) info : null, dpi);
        }
        return null;
    }

    private Drawable drawable(String name, int dpi) {
        try {
            int id = id(name);
            if (id == 0) return null;
            return res.getDrawableForDensity(id, dpi, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The app's own icon on the pack's iconback, cut by its iconmask, under its iconupon, at its scale. */
    private Drawable shaped(Context context, PackageItemInfo info, ApplicationInfo appInfo, int dpi) {
        if (appInfo == null) return null;
        Drawable original;
        try {
            Resources appRes = context.getPackageManager().getResourcesForApplication(appInfo);
            int icon = info.icon != 0 ? info.icon : appInfo.icon;
            if (icon == 0) return null;
            original = appRes.getDrawableForDensity(icon, dpi, null);
        } catch (Throwable t) {
            return null;
        }
        if (original == null) return null;
        Drawable back = backs.isEmpty() ? null
                : drawable(backs.get(Math.abs(info.packageName.hashCode()) % backs.size()), dpi);
        Drawable maskDrawable = mask != null ? drawable(mask, dpi) : null;
        Drawable uponDrawable = upon != null ? drawable(upon, dpi) : null;

        int size = back != null && back.getIntrinsicWidth() > 0 ? back.getIntrinsicWidth() : 192;
        size = Math.max(96, Math.min(size, 512));
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        if (back != null) {
            back.setBounds(0, 0, size, size);
            back.draw(canvas);
        }

        // The app's icon in a layer of its own, so the mask cuts only the icon, not the back.
        Bitmap layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas layerCanvas = new Canvas(layer);
        int inner = Math.round(size * scale);
        int offset = (size - inner) / 2;
        // An adaptive icon draws itself in the phone's icon shape.
        original.setBounds(offset, offset, offset + inner, offset + inner);
        original.draw(layerCanvas);
        if (maskDrawable != null) {
            Bitmap maskBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            maskDrawable.setBounds(0, 0, size, size);
            maskDrawable.draw(new Canvas(maskBitmap));
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            layerCanvas.drawBitmap(maskBitmap, 0, 0, paint);
            maskBitmap.recycle();
        }
        canvas.drawBitmap(layer, 0, 0, null);
        layer.recycle();
        if (uponDrawable != null) {
            uponDrawable.setBounds(0, 0, size, size);
            uponDrawable.draw(canvas);
        }
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}
