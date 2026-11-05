# OnePlus System Launcher (com.android.launcher, ColorOS / OxygenOS 16): ColorOS's shared answers
# (../coloros-standins/known.py) plus the Android framework classes the launcher names that Android 15 does not have
# (Android 16 classes such as android.window.DesktopModeFlags, and classes ColorOS keeps in its framework such as
# com.android.internal.protolog.ProtoLog). Read by ../xiaomi-standins/gen.py through gen.py next to this file.
import importlib.util, json, os, re, sys

_here = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location('coloros_known', os.path.join(_here, '..', 'coloros-standins', 'known.py'))
_base = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_base)
for _k in dir(_base):
    if not _k.startswith('__'):
        globals()[_k] = getattr(_base, _k)

HEADER = ('Generated stand-in (.github/scripts/coloros-launcher): names and signatures {app} uses, answering "absent" '
          'off ColorOS and on Android versions without them (no OPPO or Google code).')

SUPERS = dict(_base.SUPERS)
METHODS = dict(_base.METHODS)

# ColorOS's framework, and Android's own classes the phone lacks (android.*, com.android.*).
ONLY = '(?:' + _base.ONLY + r')|^L(android/|com/android/)'

# Constants that are objects of their own class (DesktopModeFlags.ENABLE_..., read and asked isTrue()): one object
# each, whose answers are "absent" like everything else; null made every read a NullPointerException.
FIELDS = dict(_base.FIELDS)
try:
    _usage = json.load(open(os.path.join(sys.argv[1], 'usage.json')))['classes']
except (IndexError, OSError, ValueError, KeyError):
    _usage = {}
_only = re.compile(ONLY)
for _c, _d in _usage.items():
    if not _only.search(_c) or _d.get('implementedBy') or _c.endswith('$Stub;') or _c in SUPERS:
        continue
    _ctors = [m for m in _d.get('methods', {}) if m.startswith('<init>(')]
    if _ctors and '<init>()V' not in _ctors:
        continue
    for _f, _ops in _d.get('fields', {}).items():
        _name, _t = _f.split(':', 1)
        if _t == _c and all(_o.startswith('s') for _o in _ops):
            FIELDS.setdefault(f'{_c}->{_f}', f'new {_c[1:-1].replace("/", ".").replace("$", ".")}()')

# What the launcher's own classes extend, where gen.py would guess wrong (a "new X" followed by an unrelated throw
# reads as a thrown X, which gave these an Exception base) or finds no Android base.
SUPERS.update({
    # OPPO's dispatch handler is a Handler (the launcher's worker handlers extend it).
    'Lcom/oplus/dispatch/OCDHandler;': 'Landroid/os/Handler;',
    'Lcom/oplus/wrapper/content/res/Configuration;': 'Ljava/lang/Object;',
    'Lcom/oplus/wrapper/view/SurfaceControl$Transaction;': 'Ljava/lang/Object;',
    'Lcom/oplus/nearx/track/TrackApi$StaticConfig$Builder;': 'Ljava/lang/Object;',
    # ColorOS's copy of ViewPager (the gesture guides' carousels).
    'Lcom/oplus/widget/OplusViewPager;': 'Landroid/view/ViewGroup;',
    # ColorOS's task view (the floating-window container the launcher subclasses) is a SurfaceView.
    'Lcom/oplus/flexiblewindow/FlexibleTaskView;': 'Landroid/view/SurfaceView;',
})

# ColorOS's work dispatcher (com.oplus.dispatch), which runs all of the launcher's background work: Android's own threads
# (RUNTIME.Dispatch), not "absent" -- an executor that drops its work leaves the home screen empty.
SUPERS.update({
    'Lcom/oplus/dispatch/OCDExecutorService;': 'Ljava/util/concurrent/ThreadPoolExecutor;',
    'Lcom/oplus/dispatch/OCDExecutor;': 'Ljava/util/concurrent/ThreadPoolExecutor;',
})
_POOL = ('super({n}, {n}, 10L, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), '
         'RUNTIME.Dispatch.threads({name})); allowCoreThreadTimeOut(true);')
_N = 'Math.max(2, Runtime.getRuntime().availableProcessors())'
FIELDS.update({
    'Lcom/oplus/dispatch/QosType;->QOS_MAINTENANCE:Ljava/lang/Integer;': 'Integer.valueOf(1)',
    'Lcom/oplus/dispatch/QosType;->QOS_USER_INTERACTIVE:Ljava/lang/Integer;': 'Integer.valueOf(4)',
})
METHODS.update({
    # A concurrent queue is a pool of threads; every other queue runs its work in order on one thread.
    'Lcom/oplus/dispatch/OCDExecutorService;-><init>(Ljava/lang/String;Lcom/oplus/dispatch/QueueAttr;Lcom/oplus/dispatch/DispatchQueue;)V':
        _POOL.format(n=f'p1 == com.oplus.dispatch.QueueAttr.QUEUE_CONCURRENT ? {_N} : 1', name='p0'),
    'Lcom/oplus/dispatch/OCDExecutor;-><init>(Lcom/oplus/dispatch/SourceQueue;)V': _POOL.format(n='1', name='"ocd-executor"'),
    'Lcom/oplus/dispatch/OCDHandler;-><init>(Lcom/oplus/dispatch/OCDMessageQueue;)V': 'super(RUNTIME.Dispatch.looperOf(p0));',
    'Lcom/oplus/dispatch/OCDHandler;-><init>(Ljava/lang/String;)V': 'super(RUNTIME.Dispatch.looper(p0));',
    'Lcom/oplus/dispatch/OCDHandler;-><init>(Ljava/lang/String;Landroid/os/Handler$Callback;)V': 'super(RUNTIME.Dispatch.looper(p0), p1);',
    'Lcom/oplus/dispatch/OCDHandler;->getOcdMessageQueue()Lcom/oplus/dispatch/OCDMessageQueue;':
        'return RUNTIME.Dispatch.queue(getLooper());',
    'Lcom/oplus/dispatch/OCDMessageQueue;->getCurrentThreadOCDMessageQueue()Lcom/oplus/dispatch/OCDMessageQueue;':
        'return RUNTIME.Dispatch.queue(android.os.Looper.myLooper());',
    'Lcom/oplus/dispatch/OCDMessageQueue;->quit(Z)V': 'RUNTIME.Dispatch.quit(this, p0);',
    'Lcom/oplus/dispatch/QueueManager;->queueCreate(Ljava/lang/String;Lcom/oplus/dispatch/QueueAttr;)Lcom/oplus/dispatch/SourceQueue;':
        'return new com.oplus.dispatch.SourceQueue();',
    'Lcom/oplus/dispatch/QueueManager;->messageQueueCreate(Ljava/lang/String;Lcom/oplus/dispatch/QueueAttr;Lcom/oplus/dispatch/DispatchQueue;)Lcom/oplus/dispatch/OCDMessageQueue;':
        'return RUNTIME.Dispatch.queue(RUNTIME.Dispatch.looper(p0));',
    'Lcom/oplus/dispatch/WorkDispatch;->dispatchAsync(Lcom/oplus/dispatch/SourceQueue;Ljava/lang/Runnable;)I':
        'return RUNTIME.Dispatch.async(p0, p1);',
})

# ColorOS's partition directories (/my_company, /my_product, ...): paths that do not exist elsewhere, so reading from
# them finds nothing, instead of null (the theme engine builds its paths from them in a class initializer).
for _n, _d in (('Bigball', 'my_bigball'), ('Carrier', 'my_carrier'), ('Company', 'my_company'), ('Country', 'my_country'),
               ('Engineering', 'my_engineering'), ('Operator', 'my_operator'), ('Product', 'my_product'),
               ('Region', 'my_region'), ('Stock', 'my_stock')):
    METHODS[f'Landroid/os/OplusBaseEnvironment;->getMy{_n}Directory()Ljava/io/File;'] = f'return new java.io.File("/{_d}");'
METHODS['Landroid/os/OplusBaseEnvironment;->getOplusCustomDirectory()Ljava/io/File;'] = 'return new java.io.File("/oplus_custom");'

# ColorOS 14+'s wrappers of Android's hidden API (com.oplus.wrapper.*): the launcher asks them for things every app can
# have -- its own application, a view's visibility, a component's name, a window's bounds -- so they answer with
# Android's public equivalents (the wrapped object remembered by RUNTIME.ColorOS.wrap).
_W = 'Lcom/oplus/wrapper/'
METHODS.update({
    f'{_W}app/ActivityThread;->currentApplication()Landroid/app/Application;':
        'try { return (android.app.Application) Class.forName("android.app.ActivityThread")'
        '.getMethod("currentApplication").invoke(null); } catch (ReflectiveOperationException | RuntimeException e) '
        '{ return null; }',
    f'{_W}content/Context;-><init>(Landroid/content/Context;)V': 'RUNTIME.ColorOS.wrap(this, p0);',
    f'{_W}content/Context;->getMainThreadHandler()Landroid/os/Handler;':
        'return new android.os.Handler(android.os.Looper.getMainLooper());',
    f'{_W}view/View;-><init>(Landroid/view/View;)V': 'RUNTIME.ColorOS.wrap(this, p0);',
    f'{_W}view/View;->isVisibleToUser()Z':
        'android.view.View v = RUNTIME.ColorOS.wrapped(this, android.view.View.class); '
        'return v != null && v.isShown() && v.getGlobalVisibleRect(new android.graphics.Rect());',
    f'{_W}content/pm/ComponentInfo;-><init>(Landroid/content/pm/ComponentInfo;)V': 'RUNTIME.ColorOS.wrap(this, p0);',
    f'{_W}content/pm/ComponentInfo;->getComponentName()Landroid/content/ComponentName;':
        'android.content.pm.ComponentInfo c = RUNTIME.ColorOS.wrapped(this, android.content.pm.ComponentInfo.class); '
        'return c == null ? null : new android.content.ComponentName(c.packageName, c.name);',
    f'{_W}util/SparseArray;-><init>(Landroid/util/SparseArray;)V': 'RUNTIME.ColorOS.wrap(this, p0);',
    f'{_W}util/SparseArray;->removeReturnOld(I)Ljava/lang/Object;':
        'android.util.SparseArray a = RUNTIME.ColorOS.wrapped(this, android.util.SparseArray.class); '
        'if (a == null) return null; Object old = a.get(p0); a.remove(p0); return old;',
    # androidx's copy of Android's path parser, which the app bundles (icon shapes are path data).
    f'{_W}util/PathParser;->createPathFromPathData(Ljava/lang/String;)Landroid/graphics/Path;':
        'try { return (android.graphics.Path) Class.forName("androidx.core.graphics.PathParser")'
        '.getMethod("createPathFromPathData", String.class).invoke(null, p0); } '
        'catch (ReflectiveOperationException | RuntimeException e) { return new android.graphics.Path(); }',
    f'{_W}os/ServiceManager;->getService(Ljava/lang/String;)Landroid/os/IBinder;':
        'try { return (android.os.IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", '
        'String.class).invoke(null, p0); } catch (ReflectiveOperationException | RuntimeException e) { return null; }',
    f'{_W}os/ServiceManager;->checkService(Ljava/lang/String;)Landroid/os/IBinder;':
        'try { return (android.os.IBinder) Class.forName("android.os.ServiceManager").getMethod("checkService", '
        'String.class).invoke(null, p0); } catch (ReflectiveOperationException | RuntimeException e) { return null; }',
    f'{_W}view/Choreographer;->getSfInstance()Landroid/view/Choreographer;':
        'return android.os.Looper.myLooper() == null ? null : android.view.Choreographer.getInstance();',
    f'{_W}os/Trace;->traceBegin(JLjava/lang/String;)V': 'android.os.Trace.beginSection(p1);',
    f'{_W}os/Trace;->traceEnd(J)V': 'android.os.Trace.endSection();',
    # The app's own user and the display's window bounds.
    f'{_W}os/UserManager;->getUserInfo(I)Lcom/oplus/wrapper/content/pm/UserInfo;': 'return new com.oplus.wrapper.content.pm.UserInfo();',
    f'{_W}content/pm/UserInfo;->getUserHandle()Landroid/os/UserHandle;': 'return android.os.Process.myUserHandle();',
    f'{_W}content/res/Configuration;->getWindowConfiguration()Lcom/oplus/wrapper/app/WindowConfiguration;':
        'return new com.oplus.wrapper.app.WindowConfiguration();',
    f'{_W}app/WindowConfiguration;->getBounds()Landroid/graphics/Rect;': 'return RUNTIME.Reflect.windowConfiguration.getBounds();',
    f'{_W}app/WindowConfiguration;->getMaxBounds()Landroid/graphics/Rect;': 'return RUNTIME.Reflect.windowConfiguration.getMaxBounds();',
    f'{_W}app/WindowConfiguration;->getRotation()I': 'return RUNTIME.Reflect.windowConfiguration.getRotation();',
})
FIELDS.update({
    f'{_W}app/WindowConfiguration;->ROTATION_UNDEFINED:I': '-1',
    f'{_W}app/WindowConfiguration;->WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:I': '4',
    f'{_W}app/ActivityTaskManager;->INVALID_TASK_ID:I': '-1',
})

# Empty values instead of null for the launcher's plain value types (a minimized-window Bundle it asks containsKey,
# window bounds it asks width(), package lists it adds to its own): an absent ColorOS answer is then "nothing in it".
_EMPTY = {'Landroid/os/Bundle;': 'android.os.Bundle', 'Landroid/graphics/Rect;': 'android.graphics.Rect',
          'Landroid/graphics/RectF;': 'android.graphics.RectF', 'Landroid/graphics/Point;': 'android.graphics.Point',
          'Ljava/util/ArrayList;': 'java.util.ArrayList<>', 'Ljava/util/HashMap;': 'java.util.HashMap<>',
          'Ljava/util/HashSet;': 'java.util.HashSet<>'}
for _c, _d in _usage.items():
    if not _only.search(_c):
        continue
    for _m in _d.get('methods', {}):
        _ret = _m[_m.index(')') + 1:]
        if _ret not in _EMPTY or _m.startswith('<'):
            continue
        _ps = _m[_m.index('(') + 1:_m.index(')')]
        if _m.startswith('get') and _ps.endswith(_ret):
            continue  # gen.py answers a getter's own default argument
        METHODS.setdefault(f'{_c}->{_m}', f'return new {_EMPTY[_ret]}();')

# ColorOS's spring animation is a DynamicAnimation; its chained setters hand back the same animation.
SUPERS['Lcom/oplus/animation/SpringAnimation;'] = 'Lcom/oplus/animation/DynamicAnimation;'
for _c, _base in list(SUPERS.items()):
    for _m, _kinds in _usage.get(_c, {}).get('methods', {}).items():
        if _m.endswith(')' + _base) and 'static' not in _kinds and not _m.startswith('<'):
            METHODS.setdefault(f'{_c}->{_m}', 'return this;')

# ColorOS hangs an extra render node on a view (folder icon previews record into it and draw it): a render node of the
# launcher's own, which the preview records and draws as before (attaching it to the view is ColorOS's own addition).
METHODS['Landroid/view/OplusViewCompat;->addRenderNode(Landroid/view/View;)Landroid/graphics/RenderNode;'] = \
    'return new android.graphics.RenderNode("OplusViewCompat");'

# ColorOS's render-thread animators (the launcher uses them whenever the class exists): the same animations on the UI
# thread (RUNTIME.RenderAnimators).
_R = 'Lcom/oplus/view/OplusRenderNodeAnimator;->'
METHODS.update({
    _R + 'createInterpolatorAnimator(IFLandroid/animation/TimeInterpolator;)Lcom/oplus/view/OplusRenderNodeAnimator;':
        'return RUNTIME.RenderAnimators.create(p0, p1, p2);',
    _R + 'setTarget(Landroid/view/View;)V': 'RUNTIME.RenderAnimators.setTarget(this, p0);',
    _R + 'setStartValue(F)V': 'RUNTIME.RenderAnimators.setStartValue(this, p0);',
    _R + 'getRenderNodeAnimator()Landroid/animation/Animator;': 'return RUNTIME.RenderAnimators.animator(this);',
    _R + 'createRtAnimator(Lcom/oplus/view/IRtAnimationTarget;Landroid/view/View;)Landroid/animation/Animator;':
        'return RUNTIME.RenderAnimators.rt(p0, p1);',
    _R + 'animateToFinalPosition(Landroid/animation/Animator;F)V': 'RUNTIME.RenderAnimators.animateTo(p0, p1);',
    _R + 'createRenderValueAnimator(Landroid/animation/Animator;Landroid/view/View;)Landroid/animation/Animator;': 'return p0;',
    _R + 'createOSpringInterpolator(DDDFF)Landroid/animation/TimeInterpolator;':
        'return new android.view.animation.DecelerateInterpolator(1.5f);',
})

# ColorOS's "async" property setters (set on the view's render node off the UI thread): the view's own setters.
_A = 'Lcom/oplus/animation/OplusAsyncAnimatorUtils;->'
METHODS.update({
    _A + 'setAlpha(Landroid/graphics/RenderNode;F)Z': 'return p0 != null && p0.setAlpha(p1);',
    _A + 'setAlpha(Landroid/view/View;F)Z': 'if (p0 == null) return false; p0.setAlpha(p1); return true;',
    _A + 'setScaleX(Landroid/view/View;F)Z': 'if (p0 == null) return false; p0.setScaleX(p1); return true;',
    _A + 'setScaleY(Landroid/view/View;F)Z': 'if (p0 == null) return false; p0.setScaleY(p1); return true;',
    _A + 'setTranslationX(Landroid/view/View;F)Z': 'if (p0 == null) return false; p0.setTranslationX(p1); return true;',
    _A + 'setTranslationY(Landroid/view/View;F)Z': 'if (p0 == null) return false; p0.setTranslationY(p1); return true;',
})
