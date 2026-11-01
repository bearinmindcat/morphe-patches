package org.ungoogled.lgquickmemo;

import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.widget.TextView;

/**
 * The title of the alert layout the port adds for LG's alert_dialog_material: it carries Android's own (private)
 * alertTitle id, which LG QuickMemo+ looks up by name to set the title, as on LG's layout.
 */
public class LgAlertTitle extends TextView {
    public LgAlertTitle(Context context, AttributeSet attrs) {
        super(context, attrs);
        int id = Resources.getSystem().getIdentifier("alertTitle", "id", "android");
        if (id != 0) setId(id);
    }
}
