package org.ungoogled.honornotes;

import android.app.Activity;
import android.view.View;
import android.widget.Toast;

/**
 * Handwritten notes run on MagicOS's pen engine (com.hihonor.penkit, part of the system), which no other ROM has:
 * the new-note bubble's handwriting button is hidden, any other way into a new handwritten note says so instead of
 * crashing, and the editor does not open a handwritten (or PDF) note it cannot show.
 */
public final class Handwriting {
    private Handwriting() {
    }

    /** The new-note bubble, about to inflate its buttons: the handwriting button goes once it has. */
    public static void hideLater(View bubble) {
        if (bubble != null) bubble.post(() -> hide(bubble));
    }

    static void hide(View bubble) {
        if (bubble == null) return;
        int id = bubble.getResources().getIdentifier("write_create_view", "id", bubble.getContext().getPackageName());
        View button = id == 0 ? null : bubble.findViewById(id);
        if (button != null) button.setVisibility(View.GONE);
    }

    public static void unavailable(View bubble) {
        if (bubble == null) return;
        Toast.makeText(bubble.getContext(), "Handwritten notes need Honor's pen engine (MagicOS)", Toast.LENGTH_SHORT).show();
    }

    /** The editor about to add its handwritten-note page (fragment "PdfFragment"): closed with a note instead. */
    public static boolean blocked(Activity editor, String fragment) {
        if (editor == null || !"PdfFragment".equals(fragment)) return false;
        Toast.makeText(editor, "Handwritten notes need Honor's pen engine (MagicOS)", Toast.LENGTH_SHORT).show();
        editor.finish();
        return true;
    }
}
