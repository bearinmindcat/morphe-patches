package com.lge.sui.widget.control;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand: the time LG's time picker shows (the dialog is Android's TimePickerDialog, see SUIDrumTimePickerDialog). */
public class SUIDrumTimePicker {
    int hour;
    int minute;
    /** LG's format: 1 = 24-hour, 0 = 12-hour. */
    int format = -1;

    public int getCurrentHour() {
        return hour;
    }
    public int getCurrentMinute() {
        return minute;
    }
    public void refresh() {
    }
    public void setTimeFormat(int p0) {
        format = p0;
    }
    /** Not LG's: the stand-in dialog's way to set the time and read the format. */
    public void setTime(int hour, int minute) {
        this.hour = hour;
        this.minute = minute;
    }
    public int getTimeFormat() {
        return format;
    }
}
