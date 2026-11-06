package org.ungoogled.samsungkeyboard;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import com.samsung.android.content.clipboard.SemClipboardManager;

/**
 * The keyboard's clipboard off One UI. On a Galaxy phone Samsung's clipboard service hands the keyboard every copy and
 * the keyboard keeps its own clipboard history; elsewhere there is no such service, so the keyboard hid its clipboard.
 * This stands in for the service (Services answers "semclipboard" with it): it says the clipboard is on, and as the
 * phone's keyboard (which Android lets read the clipboard) it adds each copied text to the keyboard's history through
 * the keyboard's own clipboard provider, as Samsung's service does. Everything stays on the phone.
 */
public final class LocalClipboard extends SemClipboardManager {
    private static final String TAG = "UA";

    private static LocalClipboard instance;

    private final Context context;
    private String last;

    private LocalClipboard(Context context) {
        this.context = context;
    }

    /** The one stand-in for the process; the first call starts listening to the phone's clipboard. */
    static synchronized LocalClipboard get(Context context) {
        if (instance == null) {
            instance = new LocalClipboard(context.getApplicationContext() != null ? context.getApplicationContext() : context);
            instance.listen();
        }
        return instance;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private void listen() {
        try {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            if (clipboard == null) return;
            clipboard.addPrimaryClipChangedListener(() -> add(clipboard));
        } catch (RuntimeException e) {
            Log.w(TAG, "clipboard: not listening", e);
        }
    }

    /** Adds the phone's current clip to the keyboard's history, unless it is the one added last. */
    private synchronized void add(ClipboardManager clipboard) {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            ClipDescription description = clip.getDescription();
            if (description != null && description.getExtras() != null
                    && description.getExtras().getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)) {
                // Passwords and the like: Android marks them sensitive, the history does not keep them.
                return;
            }
            ClipData.Item item = clip.getItemAt(0);
            CharSequence text = item.getText();
            if (text == null || text.length() == 0) return;
            String plain = text.toString();
            if (plain.equals(last)) return;
            last = plain;

            StringBuilder types = new StringBuilder();
            if (description != null) {
                for (int i = 0; i < description.getMimeTypeCount(); i++) {
                    if (types.length() > 0) types.append(',');
                    types.append(description.getMimeType(i));
                }
            }
            ContentValues values = new ContentValues();
            values.put("time_stamp", System.currentTimeMillis());
            values.put("caller_app_uid", Process.myUid());
            values.put("user_id", Process.myUserHandle().hashCode());
            values.put("clip_label", description != null && description.getLabel() != null ? description.getLabel().toString() : "");
            values.put("clip_mimetypes", types.length() > 0 ? types.toString() : ClipDescription.MIMETYPE_TEXT_PLAIN);
            values.put("clip_text", plain);
            if (item.getHtmlText() != null) values.put("clip_html", item.getHtmlText());
            context.getContentResolver().insert(
                    Uri.parse("content://" + context.getPackageName() + ".provider.RichcontentProvider/clipboard"), values);
        } catch (RuntimeException e) {
            Log.w(TAG, "clipboard: copy not kept", e);
        }
    }

    /**
     * The keyboard's clipboard answers only Samsung's own packages by name, the keyboard's included: under another
     * package name (Change package name) it refused the keyboard its own clipboard history.
     */
    public static boolean isSelfPackage(String caller) {
        String own = android.app.Application.getProcessName();
        if (own == null || caller == null) return false;
        int colon = own.indexOf(':');
        return caller.equals(colon >= 0 ? own.substring(0, colon) : own);
    }

    /** The keyboard's providers trust only Samsung's signature; its own calls (this class's) come from itself. */
    public static boolean isSelf(Context context, String caller) {
        return context != null && caller != null && caller.equals(context.getPackageName());
    }
}
