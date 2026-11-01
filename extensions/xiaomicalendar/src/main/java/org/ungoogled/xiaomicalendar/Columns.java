package org.ungoogled.xiaomicalendar;

import android.database.Cursor;
import android.database.CursorWrapper;

/**
 * A cursor with the columns a query asked for, in its order: the ones Android's calendar provider has come from its
 * cursor, the ones only HyperOS's provider has read as NULL (0 as a number).
 */
final class Columns extends CursorWrapper {
    private final String[] names;
    private final int[] from;

    Columns(Cursor inner, String[] names) {
        super(inner);
        this.names = names;
        from = new int[names.length];
        for (int i = 0; i < names.length; i++) from[i] = inner.getColumnIndex(names[i]);
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

    @Override
    public String getString(int column) {
        return from[column] >= 0 ? super.getString(from[column]) : null;
    }

    @Override
    public short getShort(int column) {
        return from[column] >= 0 ? super.getShort(from[column]) : 0;
    }

    @Override
    public int getInt(int column) {
        return from[column] >= 0 ? super.getInt(from[column]) : 0;
    }

    @Override
    public long getLong(int column) {
        return from[column] >= 0 ? super.getLong(from[column]) : 0;
    }

    @Override
    public float getFloat(int column) {
        return from[column] >= 0 ? super.getFloat(from[column]) : 0;
    }

    @Override
    public double getDouble(int column) {
        return from[column] >= 0 ? super.getDouble(from[column]) : 0;
    }

    @Override
    public byte[] getBlob(int column) {
        return from[column] >= 0 ? super.getBlob(from[column]) : null;
    }

    @Override
    public boolean isNull(int column) {
        return from[column] < 0 || super.isNull(from[column]);
    }

    @Override
    public int getType(int column) {
        return from[column] >= 0 ? super.getType(from[column]) : FIELD_TYPE_NULL;
    }
}
