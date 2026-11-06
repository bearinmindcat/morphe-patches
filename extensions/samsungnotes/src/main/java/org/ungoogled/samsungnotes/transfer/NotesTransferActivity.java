package org.ungoogled.samsungnotes.transfer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Settings > General > Import notes / Export notes. Files are picked and saved with Android's file picker, so no storage
 * permission is needed. Translucent, so it sits over Settings.
 */
public final class NotesTransferActivity extends Activity {
    public static final String IMPORT = "org.ungoogled.samsungnotes.IMPORT_NOTES";
    public static final String EXPORT = "org.ungoogled.samsungnotes.EXPORT_NOTES";
    private static final int PICK = 1;
    private static final int SAVE = 2;
    private boolean pdf;
    private boolean text;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            // Recreated: a running import or export carries on in the background; a picker result is gone.
            finish();
            return;
        }
        if (EXPORT.equals(getIntent().getAction())) {
            askExport();
        } else {
            pickFiles();
        }
    }

    // ---- import

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        start(intent, PICK);
    }

    private void start(Intent intent, int request) {
        try {
            startActivityForResult(intent, request);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null) {
            finish();
            return;
        }
        if (request == PICK) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) finish();
            else runImport(uris);
        } else if (request == SAVE && data.getData() != null) {
            runExport(data.getData());
        } else {
            finish();
        }
    }

    private void runImport(final List<Uri> uris) {
        final TextView status = new TextView(builder().getContext());
        final AlertDialog progress = builder().setView(progressView(status, "Importing notes…")).setCancelable(false).create();
        progress.show();
        new Thread(() -> {
            final NotesImport.Result r = NotesImport.run(getApplicationContext(), uris, (done, total) ->
                runOnUiThread(() -> status.setText("Importing notes… " + (done + 1) + "/" + total)));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                showResult("Import notes", importSummary(r));
            });
        }, "NotesImport").start();
    }

    private static String importSummary(NotesImport.Result r) {
        StringBuilder s = new StringBuilder();
        if (r.imported == 0 && r.skipped == 0 && r.locked == 0 && r.failed == 0) {
            return "No Samsung Notes files found. Pick .sdocx files or a zip of them.";
        }
        s.append("Imported ").append(plural(r.imported, "note")).append(", skipped ").append(r.skipped)
            .append(r.skipped > 0 ? " already here." : ".");
        if (r.folders > 0) s.append("\n\nNew folders: ").append(r.folders).append('.');
        if (r.locked > 0) s.append("\n\nLocked notes left out: ").append(r.locked).append('.');
        if (r.failed > 0) s.append("\n\nCouldn't import: ").append(r.failed).append('.');
        if (r.notNotes > 0) s.append("\n\nNot Samsung Notes files: ").append(r.notNotes).append('.');
        return s.toString();
    }

    // ---- export

    private void askExport() {
        int[] preview;
        try {
            preview = NotesExport.preview(this);
        } catch (RuntimeException e) {
            preview = new int[]{0, 0};
        }
        if (preview[0] == 0) {
            showResult("Export notes", preview[1] > 0 ? "Only locked notes here: they are left out of exports." : "No notes to export.");
            return;
        }
        final boolean[] checked = {false, false};
        builder().setTitle("Export " + plural(preview[0], "note"))
            .setMultiChoiceItems(new String[]{"Add PDF copies", "Add text copies"}, checked, (d, which, on) -> checked[which] = on)
            .setPositiveButton("Export", (d, w) -> {
                pdf = checked[0];
                text = checked[1];
                String name = "Samsung Notes " + new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(new Date()) + ".zip";
                start(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, name), SAVE);
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
            .setOnCancelListener(d -> finish())
            .show();
    }

    private void runExport(final Uri target) {
        final TextView status = new TextView(builder().getContext());
        final AlertDialog progress = builder().setView(progressView(status, "Exporting notes…")).setCancelable(false).create();
        progress.show();
        final boolean pdf = this.pdf;
        final boolean text = this.text;
        new Thread(() -> {
            final NotesExport.Result r = NotesExport.run(getApplicationContext(), target, pdf, text, (done, total) ->
                runOnUiThread(() -> status.setText("Exporting notes… " + (done + 1) + "/" + total)));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.dismiss();
                showResult("Export notes", exportSummary(r));
            });
        }, "NotesExport").start();
    }

    private static String exportSummary(NotesExport.Result r) {
        if (r.writeFailed) return "The zip couldn't be written. Pick another folder and try again.";
        StringBuilder s = new StringBuilder("Exported ").append(plural(r.exported, "note")).append('.');
        if (r.locked > 0) s.append("\n\nLocked notes left out: ").append(r.locked).append('.');
        if (r.failed > 0) s.append("\n\nCouldn't export: ").append(r.failed).append('.');
        return s.toString();
    }

    // ---- shared

    private void showResult(String title, String message) {
        builder().setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener(d -> finish())
            .show();
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

    private LinearLayout progressView(TextView status, String label) {
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        LinearLayout row = new LinearLayout(builder().getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(row.getContext());
        bar.setIndeterminate(true);
        row.addView(bar);
        status.setText(label);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setPadding(pad, 0, 0, 0);
        row.addView(status);
        return row;
    }
}
