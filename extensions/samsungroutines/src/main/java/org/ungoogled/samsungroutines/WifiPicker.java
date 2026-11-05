package org.ungoogled.samsungroutines;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Wi-Fi network picker the "Wi-Fi network" condition (and a place's Wi-Fi network) opens. On One UI it is a screen
 * of Samsung's Settings; elsewhere there was none and adding a network crashed the app. It lists the network the phone
 * is on and the networks in range (Android shows Wi-Fi names only to apps allowed location), or takes a typed name,
 * and answers with the "ssid" extra like Samsung's. Routines check the network in the background, which needs
 * location "all the time": the picker asks for it once a network is picked.
 */
public final class WifiPicker extends Activity {
    static final String ACTION = "org.ungoogled.samsungroutines.PICK_WIFI_NETWORK";
    private static final int ASK_LOCATION = 1;
    private static final int ASK_BACKGROUND = 2;
    private static final String TYPE_NAME = "Enter a network name";
    private static final String LOCATION_OFF = "Turn on location to see networks";

    private final List<String> names = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private AlertDialog dialog;
    private BroadcastReceiver scans;
    private String picked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Exported only so the app's own implicit intent reaches it (Android 14+); nobody else gets a network name.
        if (!getPackageName().equals(getCallingPackage())) {
            finish();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            showList();
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, ASK_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == ASK_LOCATION) {
            showList();
        } else if (requestCode == ASK_BACKGROUND) {
            done(picked);
        }
    }

    private int theme() {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return night ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    private boolean located() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationOn() {
        LocationManager lm = getSystemService(LocationManager.class);
        return lm != null && lm.isLocationEnabled();
    }

    private void showList() {
        adapter = new ArrayAdapter<>(new android.view.ContextThemeWrapper(this, theme()), android.R.layout.simple_list_item_1, labels);
        refresh();
        dialog = new AlertDialog.Builder(this, theme())
                .setTitle("Wi-Fi network")
                .setAdapter(adapter, (d, which) -> {
                    String name = which < names.size() ? names.get(which) : null;
                    if (TYPE_NAME.equals(labels.get(which))) {
                        typeName();
                    } else if (LOCATION_OFF.equals(labels.get(which))) {
                        startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                        finish();
                    } else if (name != null) {
                        pick(name);
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .create();
        dialog.show();
        if (located()) {
            WifiManager wifi = getSystemService(WifiManager.class);
            scans = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refresh();
                }
            };
            registerReceiver(scans, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), RECEIVER_EXPORTED);
            try {
                wifi.startScan();
            } catch (RuntimeException e) {
                // Throttled: the last results are shown.
            }
        }
    }

    /** The connected network first, then the strongest in range; the typed-name entry last. */
    private void refresh() {
        names.clear();
        labels.clear();
        if (located() && locationOn()) {
            WifiManager wifi = getSystemService(WifiManager.class);
            WifiInfo info = wifi.getConnectionInfo();
            String current = info != null ? strip(info.getSSID()) : null;
            if (current != null) {
                names.add(current);
                labels.add(current + " (connected)");
            }
            List<ScanResult> results = new ArrayList<>(wifi.getScanResults());
            results.sort(Comparator.comparingInt((ScanResult r) -> r.level).reversed());
            for (ScanResult r : results) {
                String ssid = r.getWifiSsid() != null ? strip(r.getWifiSsid().toString()) : strip(r.SSID);
                if (ssid == null || names.contains(ssid)) continue;
                names.add(ssid);
                labels.add(ssid);
            }
        } else if (located()) {
            labels.add(LOCATION_OFF);
            names.add(null);
        }
        labels.add(TYPE_NAME);
        names.add(null);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private static String strip(String ssid) {
        if (ssid == null) return null;
        if (ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) ssid = ssid.substring(1, ssid.length() - 1);
        if (ssid.isEmpty() || WifiManager.UNKNOWN_SSID.equals(ssid) || "<unknown ssid>".equals(ssid)) return null;
        return ssid;
    }

    private void typeName() {
        final EditText field = new EditText(new android.view.ContextThemeWrapper(this, theme()));
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setSingleLine(true);
        new AlertDialog.Builder(this, theme())
                .setTitle(TYPE_NAME)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (name.isEmpty()) finish(); else pick(name);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    /** Routines see the network from the background only with location allowed all the time: ask once, then answer. */
    private void pick(String name) {
        picked = name;
        // The condition matches the access points seen now unless "Check network name only" is on.
        if (!names.contains(name)) {
            Toast.makeText(this, "Not in range now: turn on Check network name only", Toast.LENGTH_LONG).show();
        }
        if (!located() || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            done(name);
            return;
        }
        new AlertDialog.Builder(this, theme())
                .setTitle("Allow location all the time")
                .setMessage("Android shows Wi-Fi names only to apps allowed location. To check this network while you're not in Modes and Routines, allow location all the time.")
                .setPositiveButton("Allow", (d, w) -> requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, ASK_BACKGROUND))
                .setNegativeButton("Not now", (d, w) -> done(name))
                .setOnCancelListener(d -> done(name))
                .show();
    }

    private void done(String name) {
        if (name != null) setResult(RESULT_OK, new Intent().putExtra("ssid", name));
        finish();
    }

    @Override
    protected void onDestroy() {
        if (scans != null) {
            try {
                unregisterReceiver(scans);
            } catch (RuntimeException e) {
                // Not registered.
            }
        }
        if (dialog != null) dialog.dismiss();
        super.onDestroy();
    }
}
