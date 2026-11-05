package org.ungoogled.samsunglauncher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.widget.Toast;

/**
 * Asks for Android's widget permission with Android's own dialog ("Allow One UI Home to create widgets and access
 * their data?"), on a throwaway widget id of its own host. With "Always allow" ticked, every later bind succeeds.
 */
public class WidgetPermissionActivity extends Activity {
    private static final int HOST_ID = 0x7a51;
    private AppWidgetHost host;
    private int id;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ComponentName provider = getIntent().getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, ComponentName.class);
        UserHandle user = getIntent().getParcelableExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, UserHandle.class);
        if (provider == null) {
            finish();
            return;
        }
        host = new AppWidgetHost(this, HOST_ID);
        id = host.allocateAppWidgetId();
        Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider);
        if (user != null) bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user);
        try {
            startActivityForResult(bind, 1);
            Toast.makeText(this, "Tick \"Always allow\", then add the widget again", Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            done();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        done();
    }

    private void done() {
        if (host != null) host.deleteAppWidgetId(id);
        finish();
    }
}
