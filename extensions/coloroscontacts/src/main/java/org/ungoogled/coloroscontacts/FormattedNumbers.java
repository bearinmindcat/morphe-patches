package org.ungoogled.coloroscontacts;

import android.database.Cursor;
import android.database.CursorWrapper;

/**
 * Android's call log fills formatted_number only once the phone app has cached it (a missed call often has none);
 * ColorOS's always has one, and the app's call list fails to lay out an entry without it ("parameter formattedNumber"
 * is null). An entry without one reads its plain number there.
 */
final class FormattedNumbers extends CursorWrapper {
    private final int formatted;
    private final int number;

    private FormattedNumbers(Cursor inner, int formatted, int number) {
        super(inner);
        this.formatted = formatted;
        this.number = number;
    }

    static Cursor wrap(Cursor c) {
        if (c == null) return null;
        int formatted = c.getColumnIndex("formatted_number");
        int number = c.getColumnIndex("number");
        return formatted < 0 || number < 0 ? c : new FormattedNumbers(c, formatted, number);
    }

    private boolean filled(int column) {
        return column == formatted && super.isNull(formatted) && !super.isNull(number);
    }

    @Override
    public String getString(int column) {
        return filled(column) ? super.getString(number) : super.getString(column);
    }

    @Override
    public boolean isNull(int column) {
        return !filled(column) && super.isNull(column);
    }

    @Override
    public int getType(int column) {
        return filled(column) ? FIELD_TYPE_STRING : super.getType(column);
    }
}
