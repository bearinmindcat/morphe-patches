package org.ungoogled.lggallery;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * LG's permission request screen off LG's ROM: Gallery's screen closes, this invisible one asks Android for the
 * permissions it lacks and opens Gallery's screen again (same intent) once they are granted; refused, it closes.
 */
public class PermissionRequestActivity extends Activity {
    private static final String EXTRA_PERMISSIONS = "org.ungoogled.lggallery.permissions";
    private static final String EXTRA_TARGET = "org.ungoogled.lggallery.target";

    /** Closes [activity] and asks for [permissions] in its place. */
    public static void start(Activity activity, String[] permissions) {
        Intent intent = new Intent(activity, PermissionRequestActivity.class)
                .putExtra(EXTRA_PERMISSIONS, permissions)
                .putExtra(EXTRA_TARGET, new Intent(activity.getIntent()).setComponent(activity.getComponentName()));
        activity.startActivity(intent);
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return;
        String[] permissions = getIntent().getStringArrayExtra(EXTRA_PERMISSIONS);
        if (permissions == null || permissions.length == 0) {
            done(true);
            return;
        }
        requestPermissions(permissions, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
        done(granted);
    }

    private void done(boolean granted) {
        Intent target = getIntent().getParcelableExtra(EXTRA_TARGET, Intent.class);
        if (granted && target != null) {
            target.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(target);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
