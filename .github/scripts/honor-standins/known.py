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
}

# Fields Honor's libraries only read by reflection (none found yet), as in ../xiaomi-standins/known.py.
REFLECTED = {}

# Honor framework classes and the Android class each one extends, when neither the app's use nor SUPER_RULES shows it.
SUPERS = {}
