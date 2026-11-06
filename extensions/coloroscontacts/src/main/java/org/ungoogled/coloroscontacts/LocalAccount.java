package org.ungoogled.coloroscontacts;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.CursorWrapper;

/**
 * ColorOS keeps contacts saved on the phone in an account of its own ("local", "com.android.local"); Android's are in
 * the local account (no account name or type). Android's contacts provider deletes the contacts of any account the
 * phone does not have, so a contact saved under ColorOS's would vanish at the next account change, and the editor
 * refuses to save a contact without an account ("account not selected"). The app reads Android's local contacts as
 * ColorOS's (Rows) and writes ColorOS's local account as Android's (toAndroid).
 */
final class LocalAccount {
    static final String TYPE = "com.android.local";
    static final String NAME = "local";

    private LocalAccount() {
    }

    /** A raw contact or group written under ColorOS's local account goes to Android's local account. */
    static ContentValues toAndroid(ContentValues values) {
        if (values == null || !TYPE.equals(values.getAsString("account_type"))) return values;
        ContentValues out = new ContentValues(values);
        out.putNull("account_type");
        out.putNull("account_name");
        if (out.containsKey("data_set")) out.putNull("data_set");
        return out;
    }

    static Cursor wrap(Cursor c) {
        if (c == null) return null;
        int type = c.getColumnIndex("account_type");
        int name = c.getColumnIndex("account_name");
        return type < 0 || name < 0 ? c : new Rows(c, type, name);
    }

    /** Rows of Android's local account (no account name or type) read as ColorOS's local account. */
    private static final class Rows extends CursorWrapper {
        private final int type;
        private final int name;

        Rows(Cursor inner, int type, int name) {
            super(inner);
            this.type = type;
            this.name = name;
        }

        private boolean local(int column) {
            return (column == type || column == name) && super.isNull(type) && super.isNull(name);
        }

        @Override
        public String getString(int column) {
            if (!local(column)) return super.getString(column);
            return column == type ? TYPE : NAME;
        }

        @Override
        public boolean isNull(int column) {
            return !local(column) && super.isNull(column);
        }

        @Override
        public int getType(int column) {
            return local(column) ? FIELD_TYPE_STRING : super.getType(column);
        }
    }

    /** A selection and its arguments with ColorOS's local account (by literal or by argument) as Android's. */
    static final class Where {
        final String selection;
        final String[] args;

        Where(String selection, String[] args) {
            this.selection = selection;
            this.args = args;
        }
    }

    private static final java.util.regex.Pattern LITERAL = java.util.regex.Pattern.compile(
            "(?i)((?:[A-Za-z0-9_]+\\.)?account_(?:type|name))\\s*=\\s*'(com\\.android\\.local|local)'");

    static Where toAndroid(String selection, String[] args) {
        if (selection == null || !selection.contains("account_")) return new Where(selection, args);
        String out = LITERAL.matcher(selection).replaceAll("$1 IS NULL");
        if (args == null || args.length == 0) return new Where(out, args);
        // "account_type = ?" / "account_name = ?" whose argument is ColorOS's local account.
        StringBuilder sb = new StringBuilder();
        java.util.List<String> kept = new java.util.ArrayList<>();
        int arg = 0;
        boolean quoted = false;
        for (int i = 0; i < out.length(); i++) {
            char ch = out.charAt(i);
            if (ch == '\'') quoted = !quoted;
            if (ch != '?' || quoted) {
                sb.append(ch);
                continue;
            }
            String value = arg < args.length ? args[arg] : null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)account_(type|name)\\s*=\\s*$").matcher(sb);
            boolean local = m.find() && (("type".equalsIgnoreCase(m.group(1)) && TYPE.equals(value))
                    || ("name".equalsIgnoreCase(m.group(1)) && NAME.equals(value)));
            if (local) {
                sb.setLength(m.start() + "account_".length() + m.group(1).length());
                sb.append(" IS NULL");
            } else {
                sb.append(ch);
                if (arg < args.length) kept.add(args[arg]);
            }
            arg++;
        }
        for (; arg < args.length; arg++) kept.add(args[arg]);
        return kept.size() == args.length ? new Where(out, args) : new Where(sb.toString(), kept.toArray(new String[0]));
    }
}
