package com.lge.sui.widget.dialog;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand: LG's time picker dialog shown as Android's TimePickerDialog. */
public class SUIDrumTimePickerDialog {
    private final android.content.Context context;
    private final OnTimeSetListener listener;
    private final com.lge.sui.widget.control.SUIDrumTimePicker picker;
    private android.app.Dialog dialog;

    SUIDrumTimePickerDialog(android.content.Context context, OnTimeSetListener listener, com.lge.sui.widget.control.SUIDrumTimePicker picker) {
        this.context = context;
        this.listener = listener;
        this.picker = picker;
    }
    public SUIDrumTimePickerDialog() {
        this(null, null, new com.lge.sui.widget.control.SUIDrumTimePicker());
    }
    public void dismiss() {
        if (dialog != null) dialog.dismiss();
    }
    public com.lge.sui.widget.control.SUIDrumTimePicker getTimePicker() {
        return picker;
    }
    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
    public void show() {
        if (context == null) return;
        dialog = SuiDialogs.time(context, picker.getCurrentHour(), picker.getCurrentMinute(), picker.getTimeFormat(), (hour, minute) -> {
            picker.setTime(hour, minute);
            if (listener != null) listener.onTimeSet(picker, hour, minute);
        });
        dialog.show();
    }
    public static class Builder {
        private final android.content.Context context;
        private final OnTimeSetListener listener;
        private final com.lge.sui.widget.control.SUIDrumTimePicker picker = new com.lge.sui.widget.control.SUIDrumTimePicker();

        public Builder(android.content.Context p0, com.lge.sui.widget.dialog.SUIDrumTimePickerDialog.OnTimeSetListener p1) {
            context = p0;
            listener = p1;
        }
        public Builder() {
            this(null, null);
        }
        public com.lge.sui.widget.dialog.SUIDrumTimePickerDialog build() {
            return new com.lge.sui.widget.dialog.SUIDrumTimePickerDialog(context, listener, picker);
        }
        public com.lge.sui.widget.dialog.SUIDrumTimePickerDialog.Builder setTime(int p0, int p1, int p2) {
            picker.setTime(p0, p1);
            return this;
        }
    }
    public interface OnTimeSetListener {
        void onTimeSet(com.lge.sui.widget.control.SUIDrumTimePicker picker, int hour, int minute);
    }
}
