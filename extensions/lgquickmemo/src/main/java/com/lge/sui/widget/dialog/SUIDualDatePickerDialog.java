package com.lge.sui.widget.dialog;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand: LG's date picker dialog shown as Android's DatePickerDialog. */
public class SUIDualDatePickerDialog {
    private final android.content.Context context;
    private final OnDateSetListener listener;
    private final com.lge.sui.widget.control.SUIDualDatePicker picker = new com.lge.sui.widget.control.SUIDualDatePicker();
    private android.app.Dialog dialog;

    SUIDualDatePickerDialog(android.content.Context context, OnDateSetListener listener, com.lge.sui.widget.calendar.SUIDualCalendar calendar) {
        this.context = context;
        this.listener = listener;
        if (calendar != null) picker.setDate(0, calendar.getCurrentDate());
    }
    public SUIDualDatePickerDialog() {
        this(null, null, null);
    }
    public void dismiss() {
        if (dialog != null) dialog.dismiss();
    }
    public com.lge.sui.widget.control.SUIDualDatePicker getDualDatePicker() {
        return picker;
    }
    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
    public void show() {
        if (context == null) return;
        dialog = SuiDialogs.date(context, picker.getDate(), null, null, date -> {
            picker.setDate(0, date);
            if (listener != null) listener.onDateSet(picker, 0, date);
        });
        dialog.show();
    }
    public static class Builder {
        private final android.content.Context context;
        private final OnDateSetListener listener;
        private com.lge.sui.widget.calendar.SUIDualCalendar calendar;

        public Builder(android.content.Context p0, com.lge.sui.widget.dialog.SUIDualDatePickerDialog.OnDateSetListener p1) {
            context = p0;
            listener = p1;
        }
        public Builder() {
            this(null, null);
        }
        public com.lge.sui.widget.dialog.SUIDualDatePickerDialog build() {
            return new com.lge.sui.widget.dialog.SUIDualDatePickerDialog(context, listener, calendar);
        }
        public com.lge.sui.widget.dialog.SUIDualDatePickerDialog.Builder setDualCalendar(com.lge.sui.widget.calendar.SUIDualCalendar p0) {
            calendar = p0;
            return this;
        }
    }
    public interface OnDateSetListener {
        void onDateSet(com.lge.sui.widget.control.SUIDualDatePicker picker, int type, java.util.Calendar date);
    }
}
