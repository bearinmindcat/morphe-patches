package com.lge.sui.widget.dialog;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand: LG's calendar picker dialog shown as Android's DatePickerDialog, within the strategy's limits. */
public class SUICalendarPickerDialogRuby {
    private final android.content.Context context;
    private final com.lge.sui.widget.control.SUICalendarPickerRuby.OnDateChangedListener listener;
    private final com.lge.sui.widget.control.SUICalendarStrategy strategy;
    private final com.lge.sui.widget.control.SUICalendarPickerRuby picker = new com.lge.sui.widget.control.SUICalendarPickerRuby();
    private android.app.Dialog dialog;

    SUICalendarPickerDialogRuby(android.content.Context context, com.lge.sui.widget.control.SUICalendarPickerRuby.OnDateChangedListener listener,
            com.lge.sui.widget.calendar.SUIDualCalendar calendar, com.lge.sui.widget.control.SUICalendarStrategy strategy) {
        this.context = context;
        this.listener = listener;
        this.strategy = strategy;
        if (calendar != null) picker.getDualCalendar().setDate(calendar.getCurrentDate());
    }
    public SUICalendarPickerDialogRuby() {
        this(null, null, null, null);
    }
    public void dismiss() {
        if (dialog != null) dialog.dismiss();
    }
    public com.lge.sui.widget.control.SUICalendarPickerRuby getPicker() {
        return picker;
    }
    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
    public void refresh() {
    }
    public void show() {
        if (context == null) return;
        dialog = SuiDialogs.date(context, picker.getDualCalendar().getCurrentDate(),
                strategy == null ? null : strategy.getMinDate(), strategy == null ? null : strategy.getMaxDate(), date -> {
                    picker.getDualCalendar().setDate(date);
                    if (listener != null) listener.onDateChanged(picker, 0, date);
                });
        dialog.show();
    }
    public static class Builder {
        private final android.content.Context context;
        private final com.lge.sui.widget.control.SUICalendarPickerRuby.OnDateChangedListener listener;
        private com.lge.sui.widget.calendar.SUIDualCalendar calendar;
        private com.lge.sui.widget.control.SUICalendarStrategy strategy;

        public Builder(android.content.Context p0, com.lge.sui.widget.control.SUICalendarPickerRuby.OnDateChangedListener p1) {
            context = p0;
            listener = p1;
        }
        public Builder() {
            this(null, null);
        }
        public com.lge.sui.widget.dialog.SUICalendarPickerDialogRuby build() {
            return new com.lge.sui.widget.dialog.SUICalendarPickerDialogRuby(context, listener, calendar, strategy);
        }
        public com.lge.sui.widget.dialog.SUICalendarPickerDialogRuby.Builder setDualCalendarWithStrategy(com.lge.sui.widget.calendar.SUIDualCalendar p0, com.lge.sui.widget.control.SUICalendarStrategy p1) {
            calendar = p0;
            strategy = p1;
            return this;
        }
    }
}
