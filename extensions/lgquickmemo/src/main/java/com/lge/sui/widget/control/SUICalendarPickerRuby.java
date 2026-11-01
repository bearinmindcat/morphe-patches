package com.lge.sui.widget.control;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand: LG's calendar date picker: its dual calendar (the dialog is Android's DatePickerDialog). */
public class SUICalendarPickerRuby {
    com.lge.sui.widget.calendar.SUIDualCalendar calendar = new com.lge.sui.widget.calendar.SUIDualCalendar();

    public com.lge.sui.widget.calendar.SUIDualCalendar getDualCalendar() {
        return calendar;
    }
    public interface OnDateChangedListener {
        void onDateChanged(com.lge.sui.widget.control.SUICalendarPickerRuby picker, int type, java.util.Calendar date);
    }
}
