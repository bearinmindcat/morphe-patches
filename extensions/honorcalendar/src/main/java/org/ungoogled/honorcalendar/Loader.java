package org.ungoogled.honorcalendar;

import android.content.Context;
import android.content.CursorLoader;
import android.database.Cursor;
import android.net.Uri;

/**
 * Android's CursorLoader with its query going through Calendars: Calendar's CursorLoader subclasses (the event list of
 * its home screen widget) query inside Android's class, where the patch cannot send them to Calendars, so the patch
 * makes them extend this instead.
 */
public class Loader extends CursorLoader {
    public Loader(Context context) {
        super(context);
    }

    public Loader(Context context, Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        super(context, uri, projection, selection, selectionArgs, sortOrder);
    }

    @Override
    public Cursor loadInBackground() {
        Cursor cursor = Calendars.query(getContext().getContentResolver(), getUri(), getProjection(), getSelection(),
                getSelectionArgs(), getSortOrder());
        if (cursor != null) {
            try {
                cursor.getCount();
                cursor.registerContentObserver(new ForceLoadContentObserver());
            } catch (RuntimeException e) {
                cursor.close();
                throw e;
            }
        }
        return cursor;
    }
}
