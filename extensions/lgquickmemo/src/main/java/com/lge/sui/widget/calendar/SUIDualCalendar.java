package com.lge.sui.widget.calendar;

/**
 * Generated stand-in (.github/scripts/lg-standins): names and signatures LG QuickMemo+ uses (no LG code). Edited by hand:
 * LG's SUI "dual" (solar/lunar) calendar holds one date here: the main calendar, Gregorian only.
 */
public class SUIDualCalendar {
    private java.util.Calendar date = java.util.Calendar.getInstance();

    public SUIDualCalendar() {
    }
    public java.util.Calendar getCurrentDate() {
        return (java.util.Calendar) date.clone();
    }
    public int getType() {
        return 0;
    }
    public void setDate(java.util.Calendar p0) {
        if (p0 != null) date = (java.util.Calendar) p0.clone();
    }
    public static class Builder {
        private java.util.Calendar main;

        public Builder(android.content.Context p0) {
        }
        public Builder() {
        }
        public com.lge.sui.widget.calendar.SUIDualCalendar build() {
            com.lge.sui.widget.calendar.SUIDualCalendar calendar = new com.lge.sui.widget.calendar.SUIDualCalendar();
            calendar.setDate(main);
            return calendar;
        }
        public com.lge.sui.widget.calendar.SUIDualCalendar.Builder setMainCalendar(java.util.Calendar p0, com.lge.sui.widget.calendar.SUIDateFormatter p1) {
            main = p0;
            return this;
        }
    }
    public static class Factory {
        public static com.lge.sui.widget.calendar.SUIDualCalendar getInstance(android.content.Context p0, com.lge.sui.widget.calendar.SUIDualCalendar.Factory.CalendarType p1) {
            return new com.lge.sui.widget.calendar.SUIDualCalendar();
        }
        public static class CalendarType {
            public static com.lge.sui.widget.calendar.SUIDualCalendar.Factory.CalendarType DEFAULT = new CalendarType();
        }
    }
}
