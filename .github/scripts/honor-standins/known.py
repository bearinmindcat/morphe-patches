# Shared answers for Honor's MagicOS framework (the com.hihonor.android.* "Ex" classes, its widgets, leftovers of
# Huawei's com.huawei.android.*), read by ../xiaomi-standins/gen.py through gen.py next to this file. Keys are dex-style
# like the Xiaomi file: "Lowner;->name:type" (field initializer, a Java expression) or "Lowner;->name(params)return"
# (method body, Java statements; parameters are p0, p1, ...; RUNTIME is the app's runtime package, see runtime/).
# Off MagicOS the apps should behave as on a phone without Honor's framework: MAGIC_SDK_INT 0 (no MagicOS API level,
# so version-gated MagicOS calls and Honor's telemetry are skipped), the phone's own system properties, no Honor
# hardware or services.
import os

HEADER = ('Generated stand-in (.github/scripts/honor-standins): names and signatures {app} uses, answering "absent" '
          'off MagicOS (no Honor code).')

# Honor's framework: its own packages, Huawei's (EMUI) names MagicOS still carries, and its additions inside android.*.
ONLY = (r'^L(com/hihonor/|com/huawei/|hihonor/|huawei/|com/hnsdk/|android/hsm/|android/.*/(Hn|Hw)[A-Z]'
        r'|android/.*\$(Hn|Hw)[A-Z])')

RUNTIME_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'runtime')

# MagicOS ships widgets that are Android's own under another name (com.hihonor.android.widget.ListView extends
# android.widget.ListView, HwToolbar extends Toolbar, ...), and apps inflate them from their layouts. With SDK_BASES a
# stand-in named after an Android class extends it (SUPER_RULES, first existing candidate), keeps every member Android
# already has (no "absent" override of getAdapter() and friends) and gets LayoutInflater's constructors when the app's
# XML names it.
SDK_BASES = True
# Layout tags looked at for widgets: the framework's widget packages only (a layout may also name classes R8 removed
# from the app or its bundled libraries -- dead layouts).
INFLATED_ONLY = r'^L(com/hihonor/android/|com/huawei/android/|hihonor/android/|huawei/android/)'
SUPER_RULES = [
    (r'^Lcom/(?:hihonor|huawei)/android/widget/(?:Hw|Hn)?(\w+);$', [r'Landroid/widget/\1;', r'Landroid/view/\1;']),
    (r'^Lcom/(?:hihonor|huawei)/android/view/(?:Hw|Hn)?(\w+);$', [r'Landroid/view/\1;']),
    (r'^Lcom/(?:hihonor|huawei)/android/app/(?:Hw|Hn)?(\w*Dialog);$', [r'Landroid/app/\1;']),
    (r'^Lcom/(?:hihonor|huawei)/android/preference/(?:Hw|Hn)?(\w+);$', [r'Landroid/preference/\1;']),
]

FIELDS = {
    # No MagicOS API level: every "MAGIC_SDK_INT >= n" path (fold screens, HiView telemetry, ...) stays off.
    'Lcom/hihonor/android/os/Build$VERSION;->MAGIC_SDK_INT:I': '0',
    # The owner (system) user.
    'Lcom/hihonor/android/os/UserHandleEx;->OWNER:Landroid/os/UserHandle;': 'android.os.UserHandle.getUserHandleForUid(0)',
    # "All users" (Android's hidden UserHandle.ALL, for broadcasts): the app's own user, the only one an app without
    # INTERACT_ACROSS_USERS may address.
    'Lcom/hihonor/android/os/UserHandleEx;->ALL:Landroid/os/UserHandle;': 'android.os.Process.myUserHandle()',
}

_PROPS = {
    # MagicOS's wrapper of Android's (hidden) SystemProperties: the phone's own values, so MagicOS-only properties
    # (msc.*, ro.build.version.magic, ...) are empty and the app falls back to its non-Honor defaults.
    'get(Ljava/lang/String;)Ljava/lang/String;': 'return RUNTIME.Magic.prop(p0, "");',
    'get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;': 'return RUNTIME.Magic.prop(p0, p1);',
    'getInt(Ljava/lang/String;I)I': 'return RUNTIME.Magic.propInt(p0, p1);',
    'getLong(Ljava/lang/String;J)J': 'return RUNTIME.Magic.propLong(p0, p1);',
    'getBoolean(Ljava/lang/String;Z)Z': 'return RUNTIME.Magic.propBoolean(p0, p1);',
}

METHODS = {
    **{f'Lcom/hihonor/android/os/SystemPropertiesEx;->{k}': v for k, v in _PROPS.items()},
    **{f'Lcom/huawei/android/os/SystemPropertiesEx;->{k}': v for k, v in _PROPS.items()},
    # Android's own answers for the current user (UserHandle's id is its hashCode).
    'Lcom/hihonor/android/os/UserHandleEx;->myUserId()I': 'return android.os.Process.myUserHandle().hashCode();',
    'Lcom/hihonor/android/os/UserHandleEx;->getUserId(I)I': 'return p0 / 100000;',
    # No curved-edge ("side region") display: no extra insets.
    'Lcom/hihonor/android/view/DisplaySideRegionEx;->getSafeInsets()Landroid/graphics/Rect;': 'return new android.graphics.Rect();',
    'Lcom/hihonor/android/view/ExtDisplaySizeUtilEx;->getDisplaySafeInsets()Landroid/graphics/Rect;': 'return new android.graphics.Rect();',
    # HiView telemetry: the event an app builds goes nowhere (report() does nothing), but must exist to be built on.
    'Lcom/hihonor/android/app/HiViewEx;->byJson(ILjava/lang/String;)Lcom/hihonor/android/app/HiEventEx;': 'return new com.hihonor.android.app.HiEventEx(p0);',
    'Lcom/hihonor/android/os/UserHandleEx;->of(I)Landroid/os/UserHandle;': 'return android.os.UserHandle.getUserHandleForUid(p0 * 100000);',
    'Lcom/hihonor/android/os/UserHandleEx;->getIdentifier(Landroid/os/UserHandle;)I': 'return p0 == null ? 0 : p0.hashCode();',
    # MagicOS's wrappers of Android's own (hidden) calls: what Android answers. A ringer mode of 0 would be "silent"
    # (an alarm app then rings without sound), and "start with haptics" must still start the player.
    'Lcom/hihonor/android/media/AudioManagerEx;->getRingerModeInternal(Landroid/media/AudioManager;)I': 'return p0.getRingerMode();',
    'Lcom/hihonor/android/media/MediaPlayerEx;->startWithVibrate(Landroid/media/MediaPlayer;Ljava/lang/String;)V': 'p0.start();',
    'Lcom/hihonor/android/provider/SettingsEx$System;->getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I': 'return android.provider.Settings.System.getInt(p0, p1, p2);',
    'Lcom/hihonor/android/provider/SettingsEx$Global;->getZen_Mode()Ljava/lang/String;': 'return "zen_mode";',
    'Lcom/hihonor/android/content/IntentExEx;->getActionUserSwitched()Ljava/lang/String;': 'return "android.intent.action.USER_SWITCHED";',
    # The app's own package for the current (only) user: its own context.
    'Lcom/hihonor/android/content/ContextEx;->createPackageContextAsUser(Landroid/content/Context;Ljava/lang/String;ILandroid/os/UserHandle;)Landroid/content/Context;': 'return p0;',
    'Lcom/hihonor/android/os/storage/StorageManagerEx;->getVolumeList(Landroid/os/storage/StorageManager;)[Landroid/os/storage/StorageVolume;': 'return p0.getStorageVolumes().toArray(new android.os.storage.StorageVolume[0]);',
    'Lcom/hihonor/android/os/storage/StorageVolumeEx;->getPath(Landroid/os/storage/StorageVolume;)Ljava/lang/String;': 'return p0.getDirectory() == null ? "" : p0.getDirectory().getPath();',
    'Lcom/hihonor/android/preference/PreferenceFragmentEx;->getListView(Landroid/preference/PreferenceFragment;)Landroid/widget/ListView;': 'return p0.getView() == null ? null : (android.widget.ListView) p0.getView().findViewById(android.R.id.list);',
    # The phone's own locale data (libcore's LocaleData, which MagicOS exposes; apps ask for the default locale).
    'Lcom/hihonor/android/icu/libcore/LocaleDataEx;->getTimeFormat_Hm()Ljava/lang/String;': 'return android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "Hm");',
    'Lcom/hihonor/android/icu/libcore/LocaleDataEx;->getTimeFormat_hm()Ljava/lang/String;': 'return android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "hm");',
    'Lcom/hihonor/android/icu/libcore/LocaleDataEx;->getShortStandAloneWeekdayNames()[Ljava/lang/String;': 'return android.icu.text.DateFormatSymbols.getInstance(java.util.Locale.getDefault()).getWeekdays(android.icu.text.DateFormatSymbols.STANDALONE, android.icu.text.DateFormatSymbols.SHORT);',
    # Android's own answers for MagicOS's wrappers of resources, dates and file types.
    'Lcom/hihonor/android/systemmanager/common/HwResourceEx;->getString(Landroid/content/Context;I)Ljava/lang/String;': 'return p0.getString(p1);',
    'Lcom/hihonor/android/text/format/HwDateUtilsEx;->formatChinaDateTime(Landroid/content/Context;JI)Ljava/lang/String;': 'return android.text.format.DateUtils.formatDateTime(p0, p1, p2);',
    'Lcom/hihonor/android/media/MediaFileEx;->getMimeTypeForFile(Ljava/lang/String;)Ljava/lang/String;':
        'String t = p0 == null || p0.lastIndexOf(\'.\') < 0 ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(p0.substring(p0.lastIndexOf(\'.\') + 1).toLowerCase(java.util.Locale.ROOT)); return t == null ? "" : t;',
    # ICU transliteration (Chinese names to pinyin, for sorting) is not Android API: names stay as they are.
    'Lcom/hihonor/android/icu/text/TransliteratorEx;->transliterate(Ljava/lang/String;)Ljava/lang/String;': 'return p0;',
    # Toolbar start (cancel) and end (confirm) buttons, android:id/icon1 and icon2 as on MagicOS.
    'Lcom/hihonor/android/widget/ActionBarEx;->setStartIcon(Landroid/app/ActionBar;Lcom/hihonor/android/widget/HwToolbar;ZLandroid/graphics/drawable/Drawable;Landroid/view/View$OnClickListener;)V': 'RUNTIME.Magic.toolbarButton(p1, true, p2, p3, p4);',
    'Lcom/hihonor/android/widget/ActionBarEx;->setEndIcon(Landroid/app/ActionBar;Lcom/hihonor/android/widget/HwToolbar;ZLandroid/graphics/drawable/Drawable;Landroid/view/View$OnClickListener;)V': 'RUNTIME.Magic.toolbarButton(p1, false, p2, p3, p4);',
}

# Fields Honor's libraries only read by reflection (none found yet), as in ../xiaomi-standins/known.py.
REFLECTED = {}

# Honor framework classes and the Android class each one extends, when neither the app's use nor SUPER_RULES shows it.
SUPERS = {
    'Lcom/hihonor/android/preference/TextArrowPreference;': 'Landroid/preference/Preference;',
    # MagicOS's views that its launcher lets app widgets use (Android's launchers don't).
    'Lcom/hihonor/android/view/RemoteableView;': 'Landroid/view/View;',
    'Lcom/hihonor/android/widget/RemoteableRelativeLayout;': 'Landroid/widget/RelativeLayout;',
    # MagicOS's java.io.File for external storage paths.
    'Lcom/hihonor/android/libcore/io/ExternalStorageFile;': 'Ljava/io/File;',
    # MagicOS's sensor-hub devices (motion gestures): each device is an IHWExtDevice the manager registers.
    'Lcom/hihonor/android/hwextdevice/devices/HWExtMotion;': 'Lcom/hihonor/android/hwextdevice/devices/IHWExtDevice;',
}

# Added with Honor Video Player: the current user, as Android answers it (UserHandle's id is its hashCode).
METHODS['Lcom/hihonor/android/content/ContextEx;->getUserId(Landroid/content/Context;)I'] = \
    'return android.os.Process.myUserHandle().hashCode();'
