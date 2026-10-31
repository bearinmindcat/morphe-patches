package org.ungoogled.samsungclock;

import android.database.Cursor;
import android.database.CursorWrapper;
import android.media.RingtoneManager;
import android.net.Uri;

import java.util.Arrays;

/**
 * One UI's RingtoneManager cursor carries MediaStore columns Android's does not (volume, folder, kind, bookmark); the
 * sound picker read them unchecked and died. Here they are added: the volume from the row's own Uri, an empty folder,
 * every sound usable as an alarm, no bookmark.
 */
public final class RingtoneCursor extends CursorWrapper {
    private static final String[] EXTRA = {"volume_name", "bucket_display_name", "is_ringtone", "is_notification", "is_alarm", "bookmark"};

    private final int base;

    private RingtoneCursor(Cursor cursor) {
        super(cursor);
        base = cursor.getColumnCount();
    }

    /** Takes RingtoneManager.getCursor(). */
    public static Cursor getCursor(RingtoneManager manager) {
        Cursor cursor = manager.getCursor();
        if (cursor == null || cursor.getColumnIndex("is_alarm") >= 0) return cursor;
        return new RingtoneCursor(cursor);
    }

    private int extra(int column) {
        return column >= base ? column - base : -1;
    }

    @Override
    public int getColumnCount() {
        return base + EXTRA.length;
    }

    @Override
    public String[] getColumnNames() {
        String[] names = Arrays.copyOf(super.getColumnNames(), base + EXTRA.length);
        System.arraycopy(EXTRA, 0, names, base, EXTRA.length);
        return names;
    }

    @Override
    public int getColumnIndex(String name) {
        int index = super.getColumnIndex(name);
        if (index >= 0) return index;
        for (int i = 0; i < EXTRA.length; i++) if (EXTRA[i].equals(name)) return base + i;
        return -1;
    }

    @Override
    public int getColumnIndexOrThrow(String name) {
        int index = getColumnIndex(name);
        if (index < 0) throw new IllegalArgumentException("column '" + name + "' does not exist");
        return index;
    }

    @Override
    public String getColumnName(int column) {
        int e = extra(column);
        return e < 0 ? super.getColumnName(column) : EXTRA[e];
    }

    @Override
    public String getString(int column) {
        int e = extra(column);
        if (e < 0) return super.getString(column);
        switch (EXTRA[e]) {
            case "volume_name":
                return volume();
            case "bucket_display_name":
                return "";
            default:
                return String.valueOf(getInt(column));
        }
    }

    @Override
    public int getInt(int column) {
        int e = extra(column);
        if (e < 0) return super.getInt(column);
        return "is_alarm".equals(EXTRA[e]) ? 1 : 0;
    }

    @Override
    public long getLong(int column) {
        return extra(column) < 0 ? super.getLong(column) : getInt(column);
    }

    @Override
    public short getShort(int column) {
        return extra(column) < 0 ? super.getShort(column) : (short) getInt(column);
    }

    @Override
    public boolean isNull(int column) {
        return extra(column) < 0 && super.isNull(column);
    }

    @Override
    public int getType(int column) {
        int e = extra(column);
        if (e < 0) return super.getType(column);
        return e < 2 ? FIELD_TYPE_STRING : FIELD_TYPE_INTEGER;
    }

    /** The row's volume ("internal", "external_primary" ...), from the Uri RingtoneManager puts in its third column. */
    private String volume() {
        try {
            Uri uri = Uri.parse(super.getString(RingtoneManager.URI_COLUMN_INDEX));
            if (uri.getPathSegments().size() > 0) return uri.getPathSegments().get(0);
        } catch (RuntimeException e) {
            // Answered below.
        }
        return "internal";
    }
}
