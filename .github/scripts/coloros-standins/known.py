# Shared answers for ColorOS (OPPO / OnePlus OxygenOS / realme UI): the framework classes its apps call (com.oplus.*,
# the com.oplus.wrapper.* wrappers of Android's hidden API, older com.color.* / com.oppo.* names, ColorOS's additions
# inside android.*, HeyTap pieces an app references without bundling), read by ../xiaomi-standins/gen.py through gen.py
# next to this file. Keys are dex-style like the Xiaomi file: "Lowner;->name:type" (field initializer, a Java
# expression) or "Lowner;->name(params)return" (method body, Java statements; parameters are p0, p1, ...; RUNTIME is the
# app's runtime package, see runtime/). Off ColorOS the apps should behave as on a phone without OPPO's framework: no
# ColorOS version (OplusBuild 0), no ColorOS feature on, the phone's own system properties, Android's own answers where
# ColorOS only wraps Android, no OPPO hardware (linear vibrator, zoom windows, flexible windows, blur).
import os

HEADER = ('Generated stand-in (.github/scripts/coloros-standins): names and signatures {app} uses, answering "absent" '
          'off ColorOS (no OPPO code).')

# OPPO's framework and SDK packages, and ColorOS's additions inside android.* (class names with an Oplus/Oppo/Color
# prefix, android.common.*, OPPO's olc/secrecy services). Only classes neither the app nor the phone has are written.
ONLY = (r'^L(com/oplus/|com/coloros/|com/color/|com/oppo/|com/heytap/|com/nearme/|com/oneplus/|net/oneplus/|oplus/'
        r'|oppo/|coloros/|android/.*/I?(Oplus|Oppo|Color)|android/common/|android/os/olc/|android/secrecy/'
        r'|android/app/job/IJobInfoExt|com/android/id/impl/|com/sdid/id/)')

RUNTIME_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'runtime')

# Stand-ins that extend an Android class keep Android's own members and constructors (see ../honor-standins), e.g. the
# Bezier interpolator below is Android's PathInterpolator. COUI (OPPO's UI library) is bundled in each app, so layouts
# name no framework widgets; any that do are found with --apk.
SDK_BASES = True
INFLATED_ONLY = r'^L(com/oplus/|com/coloros/|com/color/|com/oppo/|oplus/|oppo/|android/.*/(Oplus|Oppo|Color))'

# OPPO's managers hand out their instance through more names than gen.py's default factories.
FACTORIES = r'^(getDefault|get|getOplusPackageManager|getOppoPackageManager|getService)$'

# Fields ColorOS's libraries only read by reflection (none found yet), as in ../xiaomi-standins/known.py.
REFLECTED = {}

SUPERS = {
    # ColorOS's Bezier interpolators (control points as doubles) are Android's PathInterpolator.
    'Landroid/view/animation/OplusBezierInterpolator;': 'Landroid/view/animation/PathInterpolator;',
    'Landroid/view/animation/OppoBezierInterpolator;': 'Landroid/view/animation/PathInterpolator;',
}

_USER = 'android.os.Process.myUserHandle().hashCode()'

FIELDS = {
    # No ColorOS version: every "OplusBuild version >= n" path (ColorOS-only animations, services, telemetry) stays off.
    'Lcom/oplus/os/OplusBuild$VERSION;->SDK_VERSION:I': '0',
    'Lcom/oplus/os/OplusBuild$VERSION;->SDK_SUB_VERSION:I': '0',
    'Lcom/oplus/os/OplusBuild$VERSION;->RELEASE:Ljava/lang/String;': '""',
    'Lcom/color/os/ColorBuild$VERSION;->RELEASE:Ljava/lang/String;': '""',
    # User ids as Android has them (UserHandle.USER_CURRENT = -2, USER_SYSTEM = 0, the owner = user 0).
    'Lcom/oplus/wrapper/os/UserHandle;->USER_CURRENT:I': '-2',
    'Lcom/oplus/wrapper/os/UserHandle;->USER_SYSTEM:I': '0',
    'Lcom/oplus/wrapper/os/UserHandle;->OWNER:Landroid/os/UserHandle;': 'android.os.UserHandle.getUserHandleForUid(0)',
    'Lcom/color/inner/os/UserHandleWrapper;->OWNER:Landroid/os/UserHandle;': 'android.os.UserHandle.getUserHandleForUid(0)',
    'Lcom/color/inner/os/UserHandleWrapper;->CURRENT:Landroid/os/UserHandle;': 'android.os.Process.myUserHandle()',
    # Android's own values of the wrapped constants.
    'Lcom/oplus/wrapper/os/Trace;->TRACE_TAG_VIEW:J': '8L',
    'Lcom/oplus/wrapper/view/ViewTreeObserver$InternalInsetsInfo;->TOUCHABLE_INSETS_REGION:I': '3',
}

# ColorOS's wrappers of Android's hidden SystemProperties: the phone's own values (RUNTIME.ColorOS.prop), so ColorOS-only
# properties (ro.build.version.oplusrom, persist.sys.oplus.region, ...) are empty and the app takes its own defaults.
_PROPS = {
    'get(Ljava/lang/String;)Ljava/lang/String;': 'return RUNTIME.ColorOS.prop(p0, "");',
    'get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;': 'return RUNTIME.ColorOS.prop(p0, p1);',
    'getInt(Ljava/lang/String;I)I': 'return RUNTIME.ColorOS.propInt(p0, p1);',
    'getLong(Ljava/lang/String;J)J': 'return RUNTIME.ColorOS.propLong(p0, p1);',
    'getBoolean(Ljava/lang/String;Z)Z': 'return RUNTIME.ColorOS.propBoolean(p0, p1);',
}
_PROP_CLASSES = ('Lcom/oplus/wrapper/os/SystemProperties;', 'Landroid/os/OplusSystemProperties;',
                 'Lcom/oplus/inner/os/SystemPropertiesWrapper;', 'Lcom/color/inner/os/SystemPropertiesWrapper;')

# The smooth ("squircle") round rects of ColorOS's graphics classes, drawn as Android's plain round rects on the
# Canvas / Path / Outline the stand-in wraps (COUI widgets draw their backgrounds through these when it finds them).
_C = 'android.graphics.Canvas c = RUNTIME.ColorOS.wrapped(this, android.graphics.Canvas.class); if (c != null) '
_P = 'android.graphics.Path p = RUNTIME.ColorOS.wrapped(this, android.graphics.Path.class); if (p != null) '
_O = 'android.graphics.Outline o = RUNTIME.ColorOS.wrapped(this, android.graphics.Outline.class); if (o != null) '
_GRAPHICS = {
    'Lcom/oplus/graphics/OplusCanvas;-><init>(Landroid/graphics/Canvas;)V': 'RUNTIME.ColorOS.wrap(this, p0);',
    'Lcom/oplus/graphics/OplusCanvas;->drawSmoothRoundRect(FFFFFFLandroid/graphics/Paint;F)V':
        _C + 'c.drawRoundRect(p0, p1, p2, p3, p4, p5, p6);',
    'Lcom/oplus/graphics/OplusCanvas;->drawSmoothRoundRect(Landroid/graphics/RectF;FFLandroid/graphics/Paint;F)V':
        _C + 'c.drawRoundRect(p0, p1, p2, p3);',
}
for _cls, _extra in (('OplusPath', ''), ('OplusPathAdapter', 'I')):
    _GRAPHICS.update({
        f'Lcom/oplus/graphics/{_cls};-><init>(Landroid/graphics/Path;{_extra})V': 'RUNTIME.ColorOS.wrap(this, p0);',
        f'Lcom/oplus/graphics/{_cls};->addSmoothRoundRect(FFFFFFFLandroid/graphics/Path$Direction;)V':
            _P + 'p.addRoundRect(p0, p1, p2, p3, p4, p5, p7);',
        f'Lcom/oplus/graphics/{_cls};->addSmoothRoundRect(FFFFFFLandroid/graphics/Path$Direction;)V':
            _P + 'p.addRoundRect(p0, p1, p2, p3, p4, p5, p6);',
        f'Lcom/oplus/graphics/{_cls};->addSmoothRoundRect(Landroid/graphics/RectF;FFFLandroid/graphics/Path$Direction;)V':
            _P + 'p.addRoundRect(p0, p1, p2, p4);',
        # OplusPath's (rect, radius, weight); the adapter's (rect, rx, ry)
        f'Lcom/oplus/graphics/{_cls};->addSmoothRoundRect(Landroid/graphics/RectF;FFLandroid/graphics/Path$Direction;)V':
            _P + ('p.addRoundRect(p0, p1, p2, p3);' if _extra else 'p.addRoundRect(p0, p1, p1, p3);'),
        f'Lcom/oplus/graphics/{_cls};->addSmoothRoundRect(Landroid/graphics/RectF;[FLandroid/graphics/Path$Direction;F)V':
            _P + 'p.addRoundRect(p0, p1, p2);',
        f'Lcom/oplus/graphics/{_cls};->addSmoothRoundRect(Landroid/graphics/RectF;[FLandroid/graphics/Path$Direction;)V':
            _P + 'p.addRoundRect(p0, p1, p2);',
    })
for _cls, _extra in (('OplusOutline', ''), ('OplusOutlineAdapter', 'I')):
    _GRAPHICS.update({
        f'Lcom/oplus/graphics/{_cls};-><init>(Landroid/graphics/Outline;{_extra})V': 'RUNTIME.ColorOS.wrap(this, p0);',
        f'Lcom/oplus/graphics/{_cls};->setSmoothRoundRect(IIIIFF)V': _O + 'o.setRoundRect(p0, p1, p2, p3, p4);',
        f'Lcom/oplus/graphics/{_cls};->setSmoothRoundRect(IIIIF)V': _O + 'o.setRoundRect(p0, p1, p2, p3, p4);',
        f'Lcom/oplus/graphics/{_cls};->setSmoothRoundRect(Landroid/graphics/Rect;FF)V': _O + 'o.setRoundRect(p0, p1);',
        f'Lcom/oplus/graphics/{_cls};->setSmoothRoundRect(Landroid/graphics/Rect;F)V': _O + 'o.setRoundRect(p0, p1);',
    })

METHODS = {
    **{f'{c}->{k}': v for c in _PROP_CLASSES for k, v in _PROPS.items()},
    **_GRAPHICS,
    # No ColorOS version; the device name is the phone's model.
    'Lcom/oplus/os/OplusBuild;->getOplusOSVERSION()I': 'return 0;',
    'Lcom/oplus/os/OplusBuild;->getDeviceName()Ljava/lang/String;': 'return android.os.Build.MODEL;',
    'Lcom/oplus/os/OplusBuild;->getDeviceName(Landroid/content/Context;)Ljava/lang/String;': 'return android.os.Build.MODEL;',
    'Lcom/color/os/ColorBuild;->getColorOSVERSION()I': 'return 0;',
    'Lcom/color/os/ColorBuild;->getDeviceName()Ljava/lang/String;': 'return android.os.Build.MODEL;',
    'Lcom/color/os/ColorBuild;->getDeviceName(Landroid/content/Context;)Ljava/lang/String;': 'return android.os.Build.MODEL;',
    # Android's user ids (UserHandle's id is its hashCode; a uid's user is uid / 100000).
    'Lcom/oplus/wrapper/os/UserHandle;->myUserId()I': f'return {_USER};',
    'Lcom/oplus/wrapper/os/UserHandle;->getUserId(I)I': 'return p0 / 100000;',
    'Lcom/oplus/wrapper/os/UserHandle;->getIdentifier()I': f'return {_USER};',
    'Lcom/color/inner/os/UserHandleWrapper;->myUserId()I': f'return {_USER};',
    'Lcom/color/inner/os/UserHandleWrapper;->getUserId(I)I': 'return p0 / 100000;',
    'Lcom/color/inner/os/UserHandleWrapper;->getIdentifier(Landroid/os/UserHandle;)I': 'return p0 == null ? 0 : p0.hashCode();',
    'Lcom/color/inner/os/UserHandleWrapper;->createUserHandle(I)Landroid/os/UserHandle;':
        'return android.os.UserHandle.getUserHandleForUid(p0 * 100000);',
    'Landroid/app/OplusActivityManager;->getCurrentUser()I': f'return {_USER};',
    # The window manager's hidden calls: a stand-in answering the phone's stable density and "has a navigation bar".
    'Lcom/oplus/wrapper/view/IWindowManager$Stub;->asInterface(Landroid/os/IBinder;)Lcom/oplus/wrapper/view/IWindowManager;':
        'return RUNTIME.ColorOS.absent(com.oplus.wrapper.view.IWindowManager.class);',
    # The touchable region COUI's bottom sheets would set (a hidden ViewTreeObserver listener): an empty region to fill.
    'Lcom/oplus/wrapper/view/ViewTreeObserver$InternalInsetsInfo;->getTouchableRegion()Landroid/graphics/Region;':
        'return new android.graphics.Region();',
    # Settings for the current user: Android's own (an installed app may read; a write it may not make is refused).
    'Lcom/oplus/wrapper/provider/Settings$System;->putIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)Z':
        'try { return android.provider.Settings.System.putInt(p0, p1, p2); } catch (RuntimeException e) { return false; }',
    'Lcom/oplus/wrapper/provider/Settings$System;->getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I':
        'try { return android.provider.Settings.System.getInt(p0, p1, p2); } catch (RuntimeException e) { return p2; }',
    'Lcom/oplus/wrapper/provider/Settings$Secure;->getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I':
        'try { return android.provider.Settings.Secure.getInt(p0, p1, p2); } catch (RuntimeException e) { return p2; }',
    'Lcom/oplus/wrapper/provider/Settings$Global;->getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I':
        'try { return android.provider.Settings.Global.getInt(p0, p1, p2); } catch (RuntimeException e) { return p2; }',
    'Lcom/oplus/wrapper/provider/Settings$System;->getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;':
        'try { return android.provider.Settings.System.getString(p0, p1); } catch (RuntimeException e) { return null; }',
    'Lcom/oplus/wrapper/provider/Settings$Secure;->getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;':
        'try { return android.provider.Settings.Secure.getString(p0, p1); } catch (RuntimeException e) { return null; }',
    # A slot's subscription id, through Android's public call.
    'Lcom/oplus/wrapper/telephony/SubscriptionManager;->getSubId(I)[I':
        'int s = android.telephony.SubscriptionManager.getSubscriptionId(p0); '
        'return s == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID ? null : new int[] { s };',
    # ColorOS's floating ("zoom") and flexible windows don't exist: their state is "no such window", never null.
    'Lcom/oplus/zoomwindow/OplusZoomWindowManager;->getCurrentZoomWindowState()Lcom/oplus/zoomwindow/OplusZoomWindowInfo;':
        'return new com.oplus.zoomwindow.OplusZoomWindowInfo();',
    'Lcom/color/zoomwindow/ColorZoomWindowManager;->getCurrentZoomWindowState()Lcom/color/zoomwindow/ColorZoomWindowInfo;':
        'return new com.color.zoomwindow.ColorZoomWindowInfo();',
    'Lcom/oplus/flexiblewindow/FlexibleWindowManager;->setExtraBundle(Landroid/app/ActivityOptions;Landroid/os/Bundle;)Landroid/os/Bundle;':
        'return p0 != null ? p0.toBundle() : new android.os.Bundle();',
    'Lcom/oplus/splitscreen/OplusSplitScreenManager;->getSplitScreenStatus(Ljava/lang/String;)Landroid/os/Bundle;':
        'return new android.os.Bundle();',
    # The Bezier interpolators: Android's PathInterpolator with the same control points.
    'Landroid/view/animation/OplusBezierInterpolator;-><init>(DDDDZ)V': 'super((float) p0, (float) p1, (float) p2, (float) p3);',
    'Landroid/view/animation/OppoBezierInterpolator;-><init>(DDDDZ)V': 'super((float) p0, (float) p1, (float) p2, (float) p3);',
    # The phone's storage as Android reports it (ColorOS's USB environment helpers; no separate SD card or OTG known).
    **{f'L{p}/os/{n}UsbEnvironment;->{k}': v for p, n in (('com/oplus', 'Oplus'), ('com/oppo', 'Oppo')) for k, v in {
        'getInternalSdDirectory(Landroid/content/Context;)Ljava/io/File;': 'return android.os.Environment.getExternalStorageDirectory();',
        'getExternalStorageDirectory()Ljava/io/File;': 'return android.os.Environment.getExternalStorageDirectory();',
        'getInternalPath(Landroid/content/Context;)Ljava/lang/String;': 'return android.os.Environment.getExternalStorageDirectory().getAbsolutePath();',
        'getInternalSdState(Landroid/content/Context;)Ljava/lang/String;': 'return android.os.Environment.getExternalStorageState();',
        'getExternalSdState(Landroid/content/Context;)Ljava/lang/String;': 'return android.os.Environment.MEDIA_REMOVED;',
        'isExternalSDRemoved(Landroid/content/Context;)Z': 'return true;',
        'getExternalPath(Landroid/content/Context;)Ljava/lang/String;': 'return null;',
        'getExternalSdDirectory(Landroid/content/Context;)Ljava/io/File;': 'return null;',
    }.items()},
}

# --- Added for OnePlus My Files (coloros-ports-c) ---
# HeyTap statistics: apps pass a CustomEvent where a BaseEvent is expected (NearMeStatistics.onCommon); without the base
# the ART verifier rejects the caller.
SUPERS.update({
    'Lcom/heytap/statistics/event/CustomEvent;': 'Lcom/heytap/statistics/event/BaseEvent;',
})

# --- Added for OnePlus Screen Recording (coloros-ports-c) ---
# ColorOS's OplusScreenStatusListener is an IOplusScreenStatusListener (its AIDL stub); apps cast their listener to it
# to hand it to OplusPowerManager (ClassCastException otherwise). Valid while IOplusScreenStatusListener is generated
# as a class (no interface calls on it).
SUPERS.update({
    'Lcom/oplus/os/OplusScreenStatusListener;': 'Lcom/oplus/os/IOplusScreenStatusListener;',
    # OplusScreenCapture's display-capture builder is a CaptureArgs builder; its inherited setters hand back the same
    # builder (null broke the chain: NullPointerException when Screen recording grabs its stop-time frame).
    'Lcom/oplus/screenshot/OplusScreenCapture$DisplayCaptureArgs$Builder;': 'Lcom/oplus/screenshot/OplusScreenCapture$CaptureArgs$Builder;',
})
METHODS.update({
    'Lcom/oplus/screenshot/OplusScreenCapture$DisplayCaptureArgs$Builder;->setSourceCrop(Landroid/graphics/Rect;)Lcom/oplus/screenshot/OplusScreenCapture$CaptureArgs$Builder;':
        'return this;',
    'Lcom/oplus/screenshot/OplusScreenCapture$DisplayCaptureArgs$Builder;->setExcludeLayers([Landroid/view/SurfaceControl;)Lcom/oplus/screenshot/OplusScreenCapture$CaptureArgs$Builder;':
        'return this;',
})
