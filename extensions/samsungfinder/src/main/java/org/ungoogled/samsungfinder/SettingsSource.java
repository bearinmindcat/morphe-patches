package org.ungoogled.samsungfinder;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Settings pages as Finder results. One UI's Settings answers Finder itself; elsewhere Android's settings search is
 * closed to apps, so this searches Android's public settings pages: each is named by the phone's own Settings app
 * (its label for that page, in the phone's language) and by an English name and keywords.
 */
public final class SettingsSource extends SearchSource {
    /** Settings action, English name, keywords. */
    private static final String[][] PAGES = {
        {Settings.ACTION_WIFI_SETTINGS, "Wi-Fi", "wifi wireless network internet hotspot"},
        {Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth", "bluetooth pair devices headphones"},
        {Settings.ACTION_WIRELESS_SETTINGS, "Network & internet", "network internet mobile data sim"},
        {Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode", "airplane flight mode"},
        {Settings.ACTION_DATA_USAGE_SETTINGS, "Data usage", "data usage mobile data limit"},
        {Settings.ACTION_NETWORK_OPERATOR_SETTINGS, "Mobile network", "mobile network operator carrier sim roaming"},
        {Settings.ACTION_DATA_ROAMING_SETTINGS, "Roaming", "roaming mobile data"},
        {Settings.ACTION_APN_SETTINGS, "Access point names", "apn access point mms"},
        {Settings.ACTION_VPN_SETTINGS, "VPN", "vpn private network"},
        {Settings.ACTION_NFC_SETTINGS, "NFC", "nfc contactless tap"},
        {Settings.ACTION_NFC_PAYMENT_SETTINGS, "Contactless payments", "nfc payment wallet tap pay"},
        {Settings.ACTION_CAST_SETTINGS, "Cast", "cast screen mirror tv"},
        {Settings.ACTION_DISPLAY_SETTINGS, "Display", "display screen brightness timeout font size dark theme"},
        {Settings.ACTION_NIGHT_DISPLAY_SETTINGS, "Night Light", "night light blue light filter eye comfort"},
        {Settings.ACTION_DREAM_SETTINGS, "Screen saver", "screen saver daydream"},
        {Intent.ACTION_SET_WALLPAPER, "Wallpaper", "wallpaper background theme style"},
        {Settings.ACTION_SOUND_SETTINGS, "Sound & vibration", "sound volume ringtone vibration notification sound"},
        {Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "Do Not Disturb", "do not disturb dnd silent priority"},
        {"android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "Notifications", "notifications app notifications"},
        {"android.settings.NOTIFICATION_SETTINGS", "Notifications", "notifications lock screen"},
        {Settings.ACTION_APPLICATION_SETTINGS, "Apps", "apps applications"},
        {Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS, "All apps", "apps applications installed uninstall"},
        {Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, "Default apps", "default apps browser phone sms home"},
        {Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "Install unknown apps", "unknown sources install apk"},
        {Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "Display over other apps", "overlay draw over apps"},
        {Settings.ACTION_USAGE_ACCESS_SETTINGS, "Usage access", "usage access"},
        {Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, "Battery optimization", "battery optimization doze"},
        {Settings.ACTION_BATTERY_SAVER_SETTINGS, "Battery saver", "battery saver power saving"},
        {Intent.ACTION_POWER_USAGE_SUMMARY, "Battery", "battery usage power"},
        {Settings.ACTION_INTERNAL_STORAGE_SETTINGS, "Storage", "storage space memory free up"},
        {Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location", "location gps"},
        {Settings.ACTION_SECURITY_SETTINGS, "Security", "security screen lock password pin fingerprint face unlock"},
        {Settings.ACTION_PRIVACY_SETTINGS, "Privacy", "privacy permissions"},
        {"android.intent.action.MANAGE_PERMISSIONS", "Permission manager", "permissions permission manager"},
        {Settings.ACTION_SYNC_SETTINGS, "Accounts", "accounts sync google"},
        {Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility", "accessibility talkback magnification"},
        {Settings.ACTION_CAPTIONING_SETTINGS, "Captions", "captions subtitles"},
        {Settings.ACTION_LOCALE_SETTINGS, "Languages", "language locale"},
        {Settings.ACTION_INPUT_METHOD_SETTINGS, "Keyboard", "keyboard input method ime"},
        {Settings.ACTION_HARD_KEYBOARD_SETTINGS, "Physical keyboard", "physical keyboard layout"},
        {Settings.ACTION_VOICE_INPUT_SETTINGS, "Voice input", "voice input assistant"},
        {Settings.ACTION_USER_DICTIONARY_SETTINGS, "Personal dictionary", "dictionary words"},
        {Settings.ACTION_DATE_SETTINGS, "Date & time", "date time timezone clock"},
        {Settings.ACTION_PRINT_SETTINGS, "Printing", "print printer"},
        {Settings.ACTION_HOME_SETTINGS, "Home app", "home launcher"},
        {Settings.ACTION_DEVICE_INFO_SETTINGS, "About phone", "about phone device info android version build number model"},
        {Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "Developer options", "developer options usb debugging"},
        {"android.settings.SYSTEM_UPDATE_SETTINGS", "System update", "system update software update"},
        {Settings.ACTION_SETTINGS, "Settings", "settings"},
    };

    @Override
    String label() {
        Intent settings = new Intent(Settings.ACTION_SETTINGS);
        ResolveInfo r = getContext().getPackageManager().resolveActivity(settings, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null ? r.loadLabel(getContext().getPackageManager()).toString() : "Settings";
    }

    @Override
    Intent launchIntent() {
        return new Intent(Settings.ACTION_SETTINGS);
    }

    @Override
    Intent inAppSearchIntent() {
        Intent search = new Intent(Settings.ACTION_APP_SEARCH_SETTINGS);
        return getContext().getPackageManager().resolveActivity(search, PackageManager.MATCH_DEFAULT_ONLY) != null ? search : null;
    }

    /** Whether a word of [text] starts with the query, or [text] contains it whole (for "date & t"). */
    private static boolean matches(String q, String text) {
        String t = text.toLowerCase(Locale.getDefault());
        if (q.contains(" ") || q.length() > 3) {
            if (t.contains(q)) return true;
        }
        for (String word : t.split("[\\s&/,()-]+")) {
            if (word.startsWith(q)) return true;
        }
        return false;
    }

    @Override
    List<JSONObject> search(String query, int limit) throws JSONException {
        PackageManager pm = getContext().getPackageManager();
        String q = query.toLowerCase(Locale.getDefault());
        String settingsLabel = label();
        boolean english = "en".equals(Locale.getDefault().getLanguage());
        List<JSONObject> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < PAGES.length && items.size() < limit; i++) {
            String[] page = PAGES[i];
            Intent intent = new Intent(page[0]);
            ResolveInfo r = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (r == null || r.activityInfo == null) continue;
            // In English the page's common name (Android names Bluetooth's page "Connected devices"); in other
            // languages the phone's own name for it, unless its Settings app names it only "Settings".
            String own = r.loadLabel(pm).toString();
            boolean generic = own.isEmpty() || (own.equals(settingsLabel) && !page[0].equals(Settings.ACTION_SETTINGS));
            String name = english || generic ? page[1] : own;
            if (!matches(q, own + " " + page[1] + " " + page[2]) || !seen.add(name.toLowerCase(Locale.getDefault()))) continue;
            intent.setClassName(r.activityInfo.packageName, r.activityInfo.name);
            JSONObject item = new JSONObject()
                    .put("ObjectType", "Settings")
                    .put("ObjectId", i)
                    .put("menuName", name)
                    .put("ObjectViewActions", viewAction(intent));
            int icon = r.getIconResource();
            if (icon == 0) icon = r.activityInfo.applicationInfo.icon;
            if (icon != 0) item.put("icon", "android.resource://" + r.activityInfo.packageName + "/" + icon);
            items.add(item);
        }
        return items;
    }
}
