package android.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG Gallery uses (no LG code). Edited by hand:
 * LG's text field frame (album and file name dialogs): the field it holds, with a line under it for an error ("Already
 * exists", too long) in the error colour or a character counter.
 */
public class HelperTextLayout extends LinearLayout {
    private TextView helper;
    private boolean errorEnabled = true;
    private boolean counterEnabled;
    private CharSequence error;
    private CharSequence counter;

    public HelperTextLayout(Context p0) {
        super(p0);
        setOrientation(VERTICAL);
    }
    public HelperTextLayout(Context p0, AttributeSet p1) {
        super(p0, p1);
        setOrientation(VERTICAL);
    }
    public HelperTextLayout(Context p0, AttributeSet p1, int p2) {
        super(p0, p1, p2);
        setOrientation(VERTICAL);
    }
    public HelperTextLayout(Context p0, AttributeSet p1, int p2, int p3) {
        super(p0, p1, p2, p3);
        setOrientation(VERTICAL);
    }

    private TextView helper() {
        if (helper == null) {
            helper = new TextView(getContext());
            helper.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            int side = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics()));
            helper.setPaddingRelative(side, side / 2, side, 0);
            helper.setVisibility(GONE);
            super.addView(helper, -1, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return helper;
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        // The field stays above the helper line.
        if (helper != null && (index < 0 || index > indexOfChild(helper))) index = indexOfChild(helper);
        super.addView(child, index, params);
    }

    private int errorColor() {
        TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.colorError});
        try {
            return a.getColor(0, 0xffe53935);
        } finally {
            a.recycle();
        }
    }

    private void update() {
        TextView line = helper();
        if (errorEnabled && error != null && error.length() > 0) {
            line.setText(error);
            line.setTextColor(errorColor());
            line.setVisibility(VISIBLE);
        } else if (counterEnabled && counter != null && counter.length() > 0) {
            line.setText(counter);
            TypedArray a = getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorSecondary});
            try {
                ColorStateList color = a.getColorStateList(0);
                if (color != null) line.setTextColor(color);
            } finally {
                a.recycle();
            }
            line.setVisibility(VISIBLE);
        } else {
            line.setVisibility(GONE);
        }
    }

    public void setCounter(CharSequence p0, boolean p1) {
        counter = p0;
        update();
    }
    public void setCounterEnabled(boolean p0) {
        counterEnabled = p0;
        update();
    }
    public void setError(CharSequence p0) {
        error = p0;
        update();
    }
    public void setErrorEnabled(boolean p0) {
        errorEnabled = p0;
        update();
    }
}
