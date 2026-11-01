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
    # The bundle keys of MagicOS's asset manager (any distinct names: only the stand-in reads them).
    **{f'Lcom/hihonor/android/security/hwassetmanager/HwAssetManager;->BUNDLE_{k}:Ljava/lang/String;': f'"{v}"'
       for k, v in (('AEADASSET', 'AEADAsset'), ('APPTAG', 'AppTag'), ('ASSETHANDLE', 'AssetHandle'),
                    ('ASSETTYPE', 'AssetType'), ('BATCHASSET', 'BatchAsset'), ('RESETFLAG', 'ResetFlag'))},
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
    # Calendar (Agenda): wrappers of Android's own (hidden) calls, answered with the public equivalent.
    'Lcom/hihonor/android/app/ActivityManagerEx;->getConfiguration()Landroid/content/res/Configuration;':
        'return new android.content.res.Configuration(android.content.res.Resources.getSystem().getConfiguration());',
    'Lcom/hihonor/android/os/UserHandleEx;->of(I)Landroid/os/UserHandle;':
        'return android.os.UserHandle.getUserHandleForUid(p0 * 100000);',
    'Lcom/hihonor/android/content/ContextEx;->createPackageContextAsUser(Landroid/content/Context;Ljava/lang/String;ILandroid/os/UserHandle;)Landroid/content/Context;':
        'try { return p0.createPackageContext(p1, p2); } catch (Exception e) { return null; }',
    'Lcom/hihonor/android/content/IntentExEx;->addHwFlags(Landroid/content/Intent;I)Landroid/content/Intent;': 'return p0;',
    'Lcom/hihonor/android/app/LocaleHelperEx;->getDisplayCountry(Ljava/util/Locale;Ljava/util/Locale;)Ljava/lang/String;':
        'return p0.getDisplayCountry(p1);',
    'Lcom/hihonor/android/os/storage/StorageManagerEx;->getVolumeList(Landroid/os/storage/StorageManager;)[Landroid/os/storage/StorageVolume;':
        'return p0.getStorageVolumes().toArray(new android.os.storage.StorageVolume[0]);',
    'Lcom/hihonor/android/os/storage/StorageVolumeEx;->getPath(Landroid/os/storage/StorageVolume;)Ljava/lang/String;':
        'java.io.File dir = p0.getDirectory(); return dir == null ? "" : dir.getPath();',
    'Lcom/hihonor/android/os/FileUtilsEx;->getSIRWXU()I': 'return 0700;',
    'Lcom/hihonor/android/os/FileUtilsEx;->getSIRWXG()I': 'return 070;',
    'Lcom/hihonor/android/os/FileUtilsEx;->getSIXOTH()I': 'return 01;',
    # Mode only (uid/gid -1 = unchanged in Android's FileUtils; an app cannot chown anyway).
    'Lcom/hihonor/android/os/FileUtilsEx;->setPermissions(Ljava/lang/String;III)I':
        'try { android.system.Os.chmod(p0, p1); return 0; } catch (Exception e) { return -1; }',
    # Settings.Global's zen_mode key and ZEN_MODE_OFF; MagicOS's own "systemex" settings table is absent (null).
    'Lcom/hihonor/android/provider/SettingsEx$Global;->getZen_Mode()Ljava/lang/String;': 'return "zen_mode";',
    'Lcom/hihonor/android/provider/SettingsEx$Systemex;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;':
        'return null;',
    'Lcom/hihonor/android/media/AudioManagerEx;->getRingerModeInternal(Landroid/media/AudioManager;)I': 'return p0.getRingerMode();',
    # Plays the ringtone; MagicOS's haptics synced to it are absent.
    'Lcom/hihonor/android/media/MediaPlayerEx;->startWithVibrate(Landroid/media/MediaPlayer;Ljava/lang/String;)V': 'p0.start();',
    # The hidden StaticLayout constructor, through the public builder.
    'Lcom/hihonor/android/text/StaticLayoutEx;->getStaticLayout(Ljava/lang/CharSequence;IILandroid/text/TextPaint;ILandroid/text/Layout$Alignment;Landroid/text/TextDirectionHeuristic;FFZLandroid/text/TextUtils$TruncateAt;II)Landroid/text/StaticLayout;':
        'return android.text.StaticLayout.Builder.obtain(p0, p1, p2, p3, Math.max(0, p4)).setAlignment(p5).setTextDirection(p6)'
        '.setLineSpacing(p8, p7).setIncludePad(p9).setEllipsize(p10).setEllipsizedWidth(Math.max(0, p11)).setMaxLines(p12).build();',
    'Lcom/hihonor/android/text/format/HwDateUtilsEx;->formatChinaDateRange(Landroid/content/Context;Ljava/util/Formatter;JJILjava/lang/String;)Ljava/lang/String;':
        'return android.text.format.DateUtils.formatDateRange(p0, p1, p2, p3, p4, p5).toString();',
    # libcore's LocaleData: the long stand-alone weekday names (index 1 = Sunday ... 7, [0] empty), default locale.
    'Lcom/hihonor/android/icu/libcore/LocaleDataEx;->getLongStandAloneWeekdayNames()[Ljava/lang/String;':
        'return android.icu.text.DateFormatSymbols.getInstance().getWeekdays(android.icu.text.DateFormatSymbols.STANDALONE, '
        'android.icu.text.DateFormatSymbols.WIDE);',
    # MagicOS's toolbar start ("cancel") and end ("done") icons, ids android:id/icon1 and icon2 (runtime/MagicToolbar).
    **{f'Lcom/hihonor/android/widget/ActionBarEx;->set{w}Icon(Landroid/app/ActionBar;Lcom/hihonor/android/widget/HwToolbar;ZLandroid/graphics/drawable/Drawable;Landroid/view/View$OnClickListener;)V':
       f'RUNTIME.MagicToolbar.set{w}Icon(p0, p1, p2, p3, p4);' for w in ('Start', 'End')},
    **{f'Lcom/hihonor/android/widget/ActionBarEx;->set{w}Icon(Landroid/app/ActionBar;ZLandroid/graphics/drawable/Drawable;Landroid/view/View$OnClickListener;)V':
       f'RUNTIME.MagicToolbar.set{w}Icon(p0, null, p1, p2, p3);' for w in ('Start', 'End')},
    'Lcom/hihonor/android/widget/ActionBarEx;->setEndContentDescription(Lcom/hihonor/android/widget/HwToolbar;Ljava/lang/CharSequence;)V':
        'RUNTIME.MagicToolbar.setEndContentDescription(p0, p1);',
    **{f'Lcom/hihonor/android/widget/ActionBarEx;->set{w}ContentDescription(Landroid/app/ActionBar;Ljava/lang/CharSequence;)V':
       f'RUNTIME.MagicToolbar.set{w}ContentDescription(p0, p1);' for w in ('Start', 'End')},
    # The same in its older place (com.hihonor.android.app), action bar only (Files).
    **{f'Lcom/hihonor/android/app/ActionBarEx;->set{w}Icon(Landroid/app/ActionBar;ZLandroid/graphics/drawable/Drawable;Landroid/view/View$OnClickListener;)V':
       f'RUNTIME.MagicToolbar.set{w}Icon(p0, null, p1, p2, p3);' for w in ('Start', 'End')},
    'Lcom/hihonor/android/app/ActionBarEx;->setCustomTitle(Landroid/app/ActionBar;Landroid/view/View;)V':
        'RUNTIME.MagicToolbar.setCustomTitle(p0, p1);',
    'Lcom/hihonor/android/widget/ActionBarEx;->setCustomTitle(Landroid/app/ActionBar;Landroid/view/View;)V':
        'RUNTIME.MagicToolbar.setCustomTitle(p0, p1);',
    # MagicOS's search bar open/close animations: an animator that changes nothing (callers play it in a set).
    **{f'Lcom/hihonor/android/view/HwSearchAnimationUtils;->getSearchFixed{w}Animator(Landroid/content/Context;Landroid/view/View;)Landroid/animation/Animator;':
       'return android.animation.ValueAnimator.ofFloat(0f, 1f);' for w in ('Open', 'Close')},
    # An empty keystore provider (see SUPERS).
    'Lcom/hihonor/android/security/keystore/HwUniversalKeyStoreProvider;-><init>()V':
        'super("HwUniversalKeyStore", 1.0, "absent off MagicOS");',
    # java.io's stream constructors that take a path throw FileNotFoundException; these are never built off MagicOS
    # (see SUPERS), so they wrap an unopened descriptor.
    **{f'Lcom/hihonor/android/libcore/io/ExternalStorageFile{w}Stream;-><init>({p})V': 'super(new java.io.FileDescriptor());'
       for w in ('Input', 'Output') for p in ('Ljava/io/File;', 'Ljava/lang/String;')},
    # Android's own formatting of a date/time (MagicOS adds Chinese calendar forms).
    'Lcom/hihonor/android/text/format/DateUtilsEx;->formatChinaDateTime(Landroid/content/Context;JI)Ljava/lang/String;':
        'return android.text.format.DateUtils.formatDateTime(p0, p1, p2);',
    'Lcom/hihonor/android/os/UserHandleEx;->getUserHandle(I)Landroid/os/UserHandle;':
        'return android.os.UserHandle.getUserHandleForUid(p0 * 100000);',
    # MagicOS's asset manager (a system store for an app's secrets: Files' Safe keeps its key there) ->
    # runtime/MagicAssets (Android keystore-encrypted, in the app's own preferences). Results as MagicOS gives them:
    # code 0 and the handle on insert, the asset as {"AEADAsset": value} on select.
    'Lcom/hihonor/android/security/hwassetmanager/HwAssetManager;->assetInsert(Landroid/content/Context;Landroid/os/Bundle;)Lcom/hihonor/android/security/hwassetmanager/HwAssetManager$AssetResult;': """
        String handle = RUNTIME.MagicAssets.insert(p0, p1 == null ? null : p1.getString(BUNDLE_AEADASSET));
        AssetResult result = new AssetResult();
        result.resultCode = handle == null ? -1 : 0;
        result.resultInfo = new java.util.ArrayList<>();
        if (handle != null) result.resultInfo.add(handle);
        return result;""",
    'Lcom/hihonor/android/security/hwassetmanager/HwAssetManager;->assetSelect(Landroid/content/Context;Landroid/os/Bundle;)Lcom/hihonor/android/security/hwassetmanager/HwAssetManager$AssetResult;': """
        String value = RUNTIME.MagicAssets.select(p0, p1 == null ? null : p1.getString(BUNDLE_ASSETHANDLE));
        AssetResult result = new AssetResult();
        result.resultCode = value == null ? -1 : 0;
        result.resultInfo = new java.util.ArrayList<>();
        if (value != null) {
            try { result.resultInfo.add(new org.json.JSONObject().put("AEADAsset", value).toString()); } catch (org.json.JSONException e) { result.resultCode = -1; }
        }
        return result;""",
    'Lcom/hihonor/android/security/hwassetmanager/HwAssetManager;->assetDelete(Landroid/content/Context;Landroid/os/Bundle;)Lcom/hihonor/android/security/hwassetmanager/HwAssetManager$AssetResult;': """
        AssetResult result = new AssetResult();
        result.resultCode = RUNTIME.MagicAssets.delete(p0, p1 == null ? null : p1.getString(BUNDLE_ASSETHANDLE)) ? 0 : -1;
        result.resultInfo = new java.util.ArrayList<>();
        return result;""",
    'Lcom/hihonor/android/widget/pattern/HwDialogTitle;->setElementText(Ljava/lang/CharSequence;I)V': """
        if (p1 != 0) return;
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_DialogWindowTitle);
        setText(p0);""",
    # MagicOS's navigation-bar blur view (a View the app adds and removes): an empty view.
    'Lcom/hihonor/android/widget/HwImmersiveMode;-><init>(Landroid/app/Activity;)V': 'super(p0);',
    # MagicOS's expanded app bar -> runtime/MagicAppbar (a plain app bar from the layout the port adds).
    'Lcom/hihonor/android/widget/appbar/HwExpandedAppbarController;-><init>(Landroid/app/Activity;)V': 'RUNTIME.MagicAppbar.attach(this, p0);',
    'Lcom/hihonor/android/widget/appbar/HwExpandedAppbarController;->getLayoutRes(I)I': 'return RUNTIME.MagicAppbar.layout(this);',
    'Lcom/hihonor/android/widget/appbar/HwExpandedAppbarController;->setContentView(ILandroid/view/View;)V':
        'RUNTIME.MagicAppbar.setContentView(this, p0, p1);',
    'Lcom/hihonor/android/widget/appbar/HwExpandedAppbarController;->setCustomView(Landroid/view/View;)V':
        'RUNTIME.MagicAppbar.setCustomView(this, p0);',
    'Lcom/hihonor/android/widget/appbar/HwExpandedAppbarController;->getAppbar()Landroid/view/View;': 'return RUNTIME.MagicAppbar.appbar(this);',
    'Lcom/hihonor/android/widget/appbar/HwExpandedAppbarController;->getCurrentVisibleHeight()I':
        'return RUNTIME.MagicAppbar.visibleHeight(this);',
    # MagicOS's layered vector icon (the app's uikit HnIconVectorDrawable wraps it and sets its layer colours by
    # reflection): the icon paths carry a grey placeholder fill, so the first layer's colour tints the whole icon.
    'Lcom/hihonor/android/widget/HnIconVectorDrawableUtils;->setLayerColor(II)V': 'if (p1 == 1) setTint(p0);',
    # Huawei's customisation factory: "<class>Impl" when the app has one (it checks its own conditions), else the class.
    'Lcom/hihonor/android/cust/HwCustUtils;->createObj(Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;': """
        Object[] args = p1 == null ? new Object[0] : p1;
        Class<?> type = p0;
        try { type = Class.forName(p0.getName() + "Impl", true, p0.getClassLoader()); } catch (Throwable e) { }
        for (Class<?> c : type == p0 ? new Class<?>[]{p0} : new Class<?>[]{type, p0}) {
            for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                Class<?>[] want = k.getParameterTypes();
                if (want.length != args.length) continue;
                boolean fits = true;
                for (int i = 0; i < want.length && fits; i++) fits = args[i] == null ? !want[i].isPrimitive() : (want[i].isPrimitive() || want[i].isInstance(args[i]));
                if (!fits) continue;
                try { k.setAccessible(true); return k.newInstance(args); } catch (Throwable e) { }
            }
        }
        return null;""",
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
    # Views an app's own views extend (Calendar's colour chip, widget month views): plain Android views off MagicOS.
    'Lcom/hihonor/android/widget/RemoteableFrameLayout;': 'Landroid/widget/FrameLayout;',
    # MagicOS's keystore provider (Honor's analytics SDK asks it for an attestation key): an empty Provider, which
    # has no algorithms, so the SDK's key request fails as it handles.
    'Lcom/hihonor/android/security/keystore/HwUniversalKeyStoreProvider;': 'Ljava/security/Provider;',
    # MagicOS's external-storage file classes (Files hands them out as java.io.File / streams, only where MagicOS's
    # ExternalStorageRandomAccessFile exists; off MagicOS it uses java.io's own, but the verifier needs the types).
    'Lcom/hihonor/android/libcore/io/ExternalStorageFileInputStream;': 'Ljava/io/FileInputStream;',
    'Lcom/hihonor/android/libcore/io/ExternalStorageFileOutputStream;': 'Ljava/io/FileOutputStream;',
    # MagicOS's dialog title (a title line, element 0, of a dialog's custom layout): a TextView.
    'Lcom/hihonor/android/widget/pattern/HwDialogTitle;': 'Landroid/widget/TextView;',
    # Notes: MagicOS views and drawables its own classes extend or treat as views/drawables.
    'Lcom/hihonor/android/widget/HwToolbarMenuView;': 'Landroid/widget/ActionMenuView;',
    'Lcom/hihonor/android/widget/RemoteableRelativeLayout;': 'Landroid/widget/RelativeLayout;',
    'Lcom/hihonor/android/widget/HwImmersiveMode;': 'Landroid/view/View;',
    'Lcom/hihonor/android/widget/HnIconVectorDrawable;': 'Landroid/graphics/drawable/VectorDrawable;',
    'Lcom/hihonor/android/widget/HnIconVectorDrawableUtils;': 'Landroid/graphics/drawable/VectorDrawable;',
    # MagicOS's sub-tab bar (named in a TV layout of Calendar's): a horizontal strip.
    'Lcom/hihonor/android/widget/SubTabWidget;': 'Landroid/widget/LinearLayout;',
}

# Added with Honor Video Player: the current user, as Android answers it (UserHandle's id is its hashCode).
METHODS['Lcom/hihonor/android/content/ContextEx;->getUserId(Landroid/content/Context;)I'] = \
    'return android.os.Process.myUserHandle().hashCode();'
