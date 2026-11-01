package org.ungoogled.honorcalendar;

import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.database.Cursor;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.lang.ref.WeakReference;

/**
 * Android's AsyncQueryHandler with its worker's provider calls going through Calendars: Calendar's AsyncQueryHandler
 * subclasses (the calendar list of the edit screen, event details, time zones) run their queries and writes inside
 * Android's class, where the patch cannot send them to Calendars, so the patch makes those subclasses extend this
 * instead. Same class (it extends AsyncQueryHandler), only the worker differs.
 */
public class Queries extends AsyncQueryHandler {
    private static final int EVENT_QUERY = 1, EVENT_INSERT = 2, EVENT_UPDATE = 3, EVENT_DELETE = 4;

    private WeakReference<ContentResolver> resolver;

    public Queries(ContentResolver cr) {
        super(cr);
        resolver = new WeakReference<>(cr);
    }

    @Override
    protected Handler createHandler(Looper looper) {
        return new Worker(looper);
    }

    private final class Worker extends Handler {
        Worker(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            ContentResolver r = resolver == null ? null : resolver.get();
            if (r == null) return;
            WorkerArgs args = (WorkerArgs) msg.obj;
            switch (msg.arg1) {
                case EVENT_QUERY:
                    Cursor cursor;
                    try {
                        cursor = Calendars.query(r, args.uri, args.projection, args.selection, args.selectionArgs, args.orderBy);
                        if (cursor != null) cursor.getCount();
                    } catch (Exception e) {
                        android.util.Log.w("UA", "async query failed: " + e);
                        cursor = null;
                    }
                    args.result = cursor;
                    break;
                case EVENT_INSERT:
                    args.result = Calendars.insert(r, args.uri, args.values);
                    break;
                case EVENT_UPDATE:
                    args.result = Calendars.update(r, args.uri, args.values, args.selection, args.selectionArgs);
                    break;
                case EVENT_DELETE:
                    args.result = Calendars.delete(r, args.uri, args.selection, args.selectionArgs);
                    break;
            }
            Message reply = args.handler.obtainMessage(msg.what);
            reply.obj = args;
            reply.arg1 = msg.arg1;
            reply.sendToTarget();
        }
    }
}
