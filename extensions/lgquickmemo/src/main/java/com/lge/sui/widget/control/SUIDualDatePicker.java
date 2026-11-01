package com.lge.sui.widget.control;

/** Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand: the date LG's date picker shows (the dialog is Android's DatePickerDialog, see SUIDualDatePickerDialog). */
public class SUIDualDatePicker {
    java.util.Calendar date = java.util.Calendar.getInstance();

    public java.util.Calendar getDate() {
        return (java.util.Calendar) date.clone();
    }
    public void refresh() {
    }
    public void setDate(int p0, java.util.Calendar p1) {
        if (p1 != null) date = (java.util.Calendar) p1.clone();
    }
}
