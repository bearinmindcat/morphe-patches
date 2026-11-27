package androidx.preference;

import android.content.Context;
import android.util.AttributeSet;

/** Only what the extension's own row needs to extend it; the rest goes through the patch-made Prefs bridges. */
public class Preference {
    public Preference(Context context, AttributeSet attrs) {
        throw new RuntimeException("stub");
    }

    /** The click hook. Its name differs between builds (onClick, onClick$1): the patch renames the override. */
    public void onClick() {
    }
}
