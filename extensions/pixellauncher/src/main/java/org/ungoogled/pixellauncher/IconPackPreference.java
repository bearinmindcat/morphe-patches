package org.ungoogled.pixellauncher;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.preference.Preference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The "Icon pack" row in Pixel Launcher's Home settings, and its picker. */
final class IconPackPreference extends Preference {
    private final Context context;

    IconPackPreference(Context context) {
        super(context, null);
        this.context = context;
        Prefs.setKey(this, IconPacks.ROW_KEY);
        Prefs.setTitle(this, "Icon pack");
        Prefs.setOrder(this, -1);
        updateSummary();
    }

    private void updateSummary() {
        String pkg = IconPacks.selectedPackage(context);
        CharSequence label = pkg.isEmpty() ? null : IconPacks.label(context, pkg);
        Prefs.setSummary(this, label == null ? "System icons" : label);
    }

    /** Overrides the row's click hook: the patch gives it the app's name for it. */
    @Override
    public void onClick() {
        try {
            showPicker();
        } catch (Throwable t) {
            android.util.Log.w(IconPacks.TAG, "icon pack picker", t);
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    private void showPicker() {
        PackageManager pm = context.getPackageManager();
        Map<String, CharSequence> packs = IconPacks.installedPacks(context);
        final List<String> packages = new ArrayList<>();
        final List<CharSequence> labels = new ArrayList<>();
        final List<Drawable> icons = new ArrayList<>();
        packages.add("");
        labels.add("System icons");
        icons.add(null);
        for (Map.Entry<String, CharSequence> e : packs.entrySet()) {
            packages.add(e.getKey());
            labels.add(e.getValue());
            Drawable icon = null;
            try {
                icon = pm.getApplicationIcon(e.getKey());
            } catch (Throwable ignored) {
            }
            icons.add(icon);
        }
        String selected = IconPacks.selectedPackage(context);
        final int[] choice = {Math.max(0, packages.indexOf(selected))};

        LinearLayout extra = new LinearLayout(context);
        extra.setOrientation(LinearLayout.VERTICAL);
        extra.setPadding(dp(20), dp(8), dp(20), 0);
        if (packs.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("No icon packs installed.");
            empty.setPadding(dp(4), 0, dp(4), 0);
            extra.addView(empty);
        }
        final CheckBox shape = new CheckBox(context);
        shape.setText("Shape icons the pack doesn't have");
        shape.setChecked(IconPacks.shapeOthers(context));
        shape.setEnabled(choice[0] != 0);
        if (!packs.isEmpty()) extra.addView(shape);

        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return packages.size();
            }

            @Override
            public Object getItem(int position) {
                return packages.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                Row row = convertView instanceof Row ? (Row) convertView : new Row(context);
                row.bind(icons.get(position), labels.get(position), position == choice[0]);
                return row;
            }
        };

        new AlertDialog.Builder(context)
                .setTitle("Icon pack")
                .setSingleChoiceItems(adapter, choice[0], (dialog, which) -> {
                    choice[0] = which;
                    adapter.notifyDataSetChanged();
                    shape.setEnabled(which != 0);
                })
                .setView(extra)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        IconPacks.choose(context, packages.get(choice[0]), shape.isChecked(), this::updateSummary))
                .show();
    }

    /** One pack in the picker: radio button, the pack's icon, its name. */
    private final class Row extends LinearLayout {
        private final RadioButton radio;
        private final ImageView icon;
        private final TextView label;

        Row(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(56));
            setPadding(dp(16), 0, dp(24), 0);
            radio = new RadioButton(c);
            radio.setClickable(false);
            radio.setFocusable(false);
            addView(radio);
            icon = new ImageView(c);
            LayoutParams iconParams = new LayoutParams(dp(36), dp(36));
            iconParams.setMargins(dp(8), 0, dp(16), 0);
            addView(icon, iconParams);
            label = new TextView(c);
            TypedArray a = c.obtainStyledAttributes(new int[]{android.R.attr.textAppearanceListItem});
            int appearance = a.getResourceId(0, 0);
            a.recycle();
            if (appearance != 0) label.setTextAppearance(appearance);
            addView(label, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        void bind(Drawable drawable, CharSequence text, boolean checked) {
            icon.setImageDrawable(drawable);
            icon.setVisibility(drawable == null ? INVISIBLE : VISIBLE);
            label.setText(text);
            radio.setChecked(checked);
        }
    }
}
