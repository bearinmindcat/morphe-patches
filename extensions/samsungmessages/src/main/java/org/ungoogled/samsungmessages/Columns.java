package org.ungoogled.samsungmessages;

import android.database.Cursor;
import android.database.CursorWrapper;

/**
 * A cursor with the columns a query asked for, in its order: the ones Android's telephony provider has come from its
 * cursor, the ones only Samsung's provider has read as null (0 for numbers) (see SmsProviders).
 */
final class Columns extends CursorWrapper {
    private final String[] names;
    private final int[] from;
    private final Object[] defaults;

    Columns(Cursor inner, String[] names) {
        super(inner);
        this.names = names;
        from = new int[names.length];
        defaults = new Object[names.length];
        for (int i = 0; i < names.length; i++) {
            from[i] = inner.getColumnIndex(names[i]);
            if (from[i] < 0) defaults[i] = SmsProviders.DEFAULT;
        }
    }

    @Override
    public int getColumnCount() {
        return names.length;
    }

    @Override
    public String[] getColumnNames() {
        return names.clone();
    }

    @Override
    public String getColumnName(int column) {
        if (outside(column)) return super.getColumnName(column);
        return names[column];
    }

    @Override
    public int getColumnIndex(String name) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equalsIgnoreCase(name)) return i;
        }
        // "table.column" asks for the column.
        int dot = name.lastIndexOf('.');
        return dot < 0 ? -1 : getColumnIndex(name.substring(dot + 1));
    }

    @Override
    public int getColumnIndexOrThrow(String name) {
        int i = getColumnIndex(name);
        if (i < 0) throw new IllegalArgumentException("column '" + name + "' does not exist");
        return i;
    }

    /** A column the query did not ask for (getColumnIndex said -1): the wrapped cursor answers as it would have. */
    private boolean outside(int column) {
        return column < 0 || column >= names.length;
    }

    private Number number(int column) {
        Object d = defaults[column];
        return d instanceof Number ? (Number) d : null;
    }

    @Override
    public String getString(int column) {
        if (outside(column)) return super.getString(column);
        if (from[column] >= 0) return super.getString(from[column]);
        Object d = defaults[column];
        return d == null ? null : String.valueOf(d);
    }

    @Override
    public short getShort(int column) {
        if (outside(column)) return super.getShort(column);
        if (from[column] >= 0) return super.getShort(from[column]);
        Number n = number(column);
        return n == null ? 0 : n.shortValue();
    }

    @Override
    public int getInt(int column) {
        if (outside(column)) return super.getInt(column);
        if (from[column] >= 0) return super.getInt(from[column]);
        Number n = number(column);
        return n == null ? 0 : n.intValue();
    }

    @Override
    public long getLong(int column) {
        if (outside(column)) return super.getLong(column);
        if (from[column] >= 0) return super.getLong(from[column]);
        Number n = number(column);
        return n == null ? 0 : n.longValue();
    }

    @Override
    public float getFloat(int column) {
        if (outside(column)) return super.getFloat(column);
        if (from[column] >= 0) return super.getFloat(from[column]);
        Number n = number(column);
        return n == null ? 0 : n.floatValue();
    }

    @Override
    public double getDouble(int column) {
        if (outside(column)) return super.getDouble(column);
        if (from[column] >= 0) return super.getDouble(from[column]);
        Number n = number(column);
        return n == null ? 0 : n.doubleValue();
    }

    @Override
    public byte[] getBlob(int column) {
        if (outside(column)) return super.getBlob(column);
        return from[column] >= 0 ? super.getBlob(from[column]) : null;
    }

    @Override
    public boolean isNull(int column) {
        if (outside(column)) return super.isNull(column);
        return from[column] >= 0 ? super.isNull(from[column]) : defaults[column] == null;
    }

    @Override
    public int getType(int column) {
        if (outside(column)) return super.getType(column);
        if (from[column] >= 0) return super.getType(from[column]);
        Object d = defaults[column];
        if (d == null) return FIELD_TYPE_NULL;
        return d instanceof Number ? FIELD_TYPE_INTEGER : FIELD_TYPE_STRING;
    }
}
