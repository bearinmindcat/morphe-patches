package com.lge.sui.widget.dialog;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand: LG's colour picker dialog: the palette (SUIColorPickerRuby) in an alert with Cancel / OK. */
public class SUIColorPickerDialogRuby {
    private final android.content.Context context;
    private final com.lge.sui.widget.control.color.OnSUIColorChangedListener listener;
    private final int initial;
    private OnButtonHandler handler;

    SUIColorPickerDialogRuby(android.content.Context context, com.lge.sui.widget.control.color.OnSUIColorChangedListener listener, int initial) {
        this.context = context;
        this.listener = listener;
        this.initial = initial;
    }
    public SUIColorPickerDialogRuby() {
        this(null, null, 0xff000000);
    }
    public void setOnButtonHandler(com.lge.sui.widget.dialog.SUIColorPickerDialogRuby.OnButtonHandler p0) {
        handler = p0;
    }
    public void show() {
        if (context == null) return;
        com.lge.sui.widget.control.SUIColorPickerRuby picker = new com.lge.sui.widget.control.SUIColorPickerRuby(context);
        picker.setInitialCheckedColor(initial);
        picker.setOnColorChangeListener(listener);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        picker.setPadding(pad, pad, pad, 0);
        new android.app.AlertDialog.Builder(context)
                .setView(picker)
                .setNegativeButton(android.R.string.cancel, (d, w) -> { if (handler != null) handler.onCancel(picker); })
                .setPositiveButton(android.R.string.ok, (d, w) -> { if (handler != null) handler.onSet(picker); })
                .show();
    }
    public static class Builder {
        private final android.content.Context context;
        private final com.lge.sui.widget.control.color.OnSUIColorChangedListener listener;
        private int initial = 0xff000000;

        public Builder(android.content.Context p0, com.lge.sui.widget.control.color.OnSUIColorChangedListener p1) {
            context = p0;
            listener = p1;
        }
        public Builder() {
            this(null, null);
        }
        public com.lge.sui.widget.dialog.SUIColorPickerDialogRuby build() {
            return new com.lge.sui.widget.dialog.SUIColorPickerDialogRuby(context, listener, initial);
        }
        public com.lge.sui.widget.dialog.SUIColorPickerDialogRuby.Builder setInitialColor(com.lge.sui.widget.control.color.SUIColor p0) {
            if (p0 != null) initial = p0.getColor();
            return this;
        }
        public com.lge.sui.widget.dialog.SUIColorPickerDialogRuby.Builder setPreviewColor(int p0) {
            return this;
        }
        public com.lge.sui.widget.dialog.SUIColorPickerDialogRuby.Builder setType(int p0) {
            return this;
        }
        public com.lge.sui.widget.dialog.SUIColorPickerDialogRuby.Builder useRecentlyUsedColorsView(boolean p0, java.lang.String p1) {
            return this;
        }
    }
    public interface OnButtonHandler {
        void onCancel(com.lge.sui.widget.control.SUIColorPickerRuby picker);
        void onSet(com.lge.sui.widget.control.SUIColorPickerRuby picker);
    }
}
