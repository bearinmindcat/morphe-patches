package org.ungoogled.xiaominotes.backup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * "Import MIUI backup" (Settings > Xiaomi Cloud): picks the backup file with Android's file picker (no storage
 * permission), imports it with {@link BackupImport} and shows what came in. Translucent, so it sits over Settings.
 */
public final class ImportBackupActivity extends Activity {
    private static final int PICK = 1;
    private static final String RUNNING = "running";
    private boolean running;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null && state.getBoolean(RUNNING)) {
            // Recreated while an import ran: it carries on in the background; nothing to show here any more.
            finish();
            return;
        }
        if (state == null) pick();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(RUNNING, running);
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*");
        try {
            startActivityForResult(intent, PICK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK) return;
        Uri uri = data != null ? data.getData() : null;
        if (result != RESULT_OK || uri == null) {
            finish();
            return;
        }
        start(uri);
    }

    private void start(final Uri uri) {
        running = true;
        final AlertDialog progress = builder().setView(progressView()).setCancelable(false).create();
        progress.show();
        new Thread(() -> {
            final BackupImport.Result r = BackupImport.run(getApplicationContext(), uri);
            runOnUiThread(() -> {
                running = false;
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                show(r);
            });
        }, "MiuiBackupImport").start();
    }

    private void show(BackupImport.Result r) {
        String title;
        String message;
        switch (r.problem) {
            case 0:
                title = "Import finished";
                message = summary(r);
                break;
            case MiuiBackupFile.ENCRYPTED:
                title = "Encrypted backup";
                message = "This backup is locked to your Xiaomi account and can't be opened here. On the Xiaomi phone, "
                    + "turn off backup encryption and back up Notes again.";
                break;
            case MiuiBackupFile.OTHER_APP:
                title = "Not a Notes backup";
                message = "This is a backup of " + r.otherApp + ". Pick Notes(com.miui.notes).bak.";
                break;
            case MiuiBackupFile.NO_NOTES:
                title = "No notes found";
                message = "This file has no Notes backup in it. Pick Notes(com.miui.notes).bak from MIUI/backup/AllBackup.";
                break;
            case MiuiBackupFile.NOT_A_BACKUP:
                title = "Not a MIUI backup";
                message = "Pick Notes(com.miui.notes).bak from MIUI/backup/AllBackup on the Xiaomi phone, or a zip of that folder.";
                break;
            case MiuiBackupFile.UNSUPPORTED:
                title = "Backup not supported";
                message = "This backup was made by a MIUI version this import can't read.";
                break;
            default:
                title = "Import failed";
                message = "The backup could not be read. It may be incomplete.";
                break;
        }
        // Notes fills in the list previews of new notes and offers to convert old-format ones when it starts, so it
        // starts again once something came in.
        final boolean restart = r.problem == 0 && (r.notes != 0 || r.folders > 0 || r.tasks > 0);
        builder().setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener(d -> {
                if (restart) restartNotes();
                else finish();
            })
            .show();
    }

    private void restartNotes() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch == null) {
            finish();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(launch);
        Runtime.getRuntime().exit(0);
    }

    private static String summary(BackupImport.Result r) {
        StringBuilder s = new StringBuilder();
        if (r.notes < 0) {
            s.append("Imported the backup's ").append(plural(r.backupNotes, "note")).append('.');
        } else if (r.notes == 0 && r.folders == 0 && r.tasks == 0) {
            s.append("No new notes: everything in this backup is already here.");
        } else {
            s.append("Imported ").append(plural(r.notes, "note"));
            if (r.folders >= 0) s.append(", ").append(plural(r.folders, "folder"));
            if (r.tasks > 0) s.append(" and ").append(plural(r.tasks, "task"));
            s.append('.');
            int skipped = r.skippedNotes();
            if (skipped > 0) s.append("\n\nSkipped ").append(plural(skipped, "note")).append(" already here.");
        }
        if (r.filesFailed > 0) s.append("\n\n").append(plural(r.filesFailed, "attached file")).append(" could not be restored.");
        if (r.toConvert > 0) {
            s.append("\n\n").append(r.toConvert == r.notes ? (r.notes == 1 ? "It is" : "They are") : r.toConvert + " of them are")
                .append(" in Notes' old format: tap Convert when Notes asks.");
        }
        return s.toString();
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private AlertDialog.Builder builder() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return new AlertDialog.Builder(this, night
            ? android.R.style.Theme_DeviceDefault_Dialog_Alert
            : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert);
    }

    private LinearLayout progressView() {
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        LinearLayout row = new LinearLayout(builder().getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(row.getContext());
        bar.setIndeterminate(true);
        row.addView(bar);
        TextView text = new TextView(row.getContext());
        text.setText("Importing notes…");
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setPadding(pad, 0, 0, 0);
        row.addView(text);
        return row;
    }
}
