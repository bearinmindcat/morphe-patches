package com.hihonor.android.preference;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/**
 * Stand-in for MagicOS's TextArrowPreference (hand-written, not generated: .github/scripts/honor-standins only gives
 * it its Android base): a preference row with a value ("detail") beside its title and an arrow at its end. MagicOS's
 * own puts its arrow widget in the row; without one the row's widget frame is gone and Honor Clock's row layout
 * (title start of the detail, detail start of the widget frame) squeezed the title into a tall empty column. No Honor
 * code: the arrow is the app's own layout, the detail goes to the row's detail view (MagicOS's id/detail, which the
 * patch renames android:id/text2).
 */
public class TextArrowPreference extends android.preference.Preference {
    private static final int MAGIC_DETAIL_ID = 0x02100066;
    private CharSequence detail;

    public TextArrowPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    public TextArrowPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public TextArrowPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TextArrowPreference(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        if (getWidgetLayoutResource() != 0) return;
        int arrow = context.getResources().getIdentifier("preference_widget_arrow", "layout", context.getPackageName());
        if (arrow != 0) setWidgetLayoutResource(arrow);
    }

    public void setDetail(CharSequence detail) {
        if (detail == null ? this.detail == null : detail.equals(this.detail)) return;
        this.detail = detail;
        notifyChanged();
    }

    public CharSequence getDetail() {
        return detail;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        View detailView = view.findViewById(android.R.id.text2);
        if (detailView == null) detailView = view.findViewById(MAGIC_DETAIL_ID);
        if (detailView instanceof TextView) {
            ((TextView) detailView).setText(detail);
            detailView.setVisibility(detail == null || detail.length() == 0 ? View.GONE : View.VISIBLE);
        }
    }
}
