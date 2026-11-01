package com.lge.sui.widget.dialog;

import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.text.format.DateFormat;

import java.util.Calendar;

/** Android's date and time picker dialogs for LG's SUI pickers (shared by the SUI dialog stand-ins). */
final class SuiDialogs {
    private SuiDialogs() {
    }

    interface DateSet {
        void set(Calendar date);
    }

    interface TimeSet {
        void set(int hour, int minute);
    }

    static Dialog date(Context context, Calendar initial, Calendar min, Calendar max, DateSet then) {
        Calendar start = initial == null ? Calendar.getInstance() : (Calendar) initial.clone();
        DatePickerDialog dialog = new DatePickerDialog(context, (view, year, month, day) -> {
            Calendar date = (Calendar) start.clone();
            date.set(year, month, day);
            then.set(date);
        }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH));
        if (min != null) dialog.getDatePicker().setMinDate(min.getTimeInMillis());
        if (max != null) dialog.getDatePicker().setMaxDate(max.getTimeInMillis());
        return dialog;
    }

    static Dialog time(Context context, int hour, int minute, int format, TimeSet then) {
        boolean twentyFour = format < 0 ? DateFormat.is24HourFormat(context) : format == 1;
        return new TimePickerDialog(context, (view, h, m) -> then.set(h, m), hour, minute, twentyFour);
    }
}
