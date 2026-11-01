import json, os, re, sys, collections, subprocess, argparse
sys.dont_write_bytecode = True  # no __pycache__ next to known.py
import known

# gen.py: Java stand-ins for the Xiaomi framework classes an app references (from Missing.java's usage.json),
# answering "absent" off HyperOS unless known.py says otherwise. See README.md.
ap = argparse.ArgumentParser()
ap.add_argument('work', help="Missing.java's output dir (usage.json, abstract.json)")
ap.add_argument('out', help='the extension\'s java source dir, e.g. extensions/xiaomicompass/src/main/java')
ap.add_argument('app', help='app name for the header comment, e.g. "Xiaomi Compass"')
ap.add_argument('--apk', help='the app APK: its manifest components give stand-ins their Android base class')
ap.add_argument('--extends', help='file of "Lclass; Lsuperclass;" lines that override every inferred base')
ap.add_argument('--runtime', help='also write Miui/MiuiShims/Fallbacks (runtime/) into this Java package')
ap.add_argument('--sdk', help='android.jar to compile against (default: newest under $ANDROID_HOME or ~/android-sdk)')
ap.add_argument('--only', help='regex: only classes matching it (default: the known file\'s ONLY, else Xiaomi framework packages)')
ap.add_argument('--known', help='another vendor\'s known.py (default: the one next to this script); see honor-standins/')
a = ap.parse_args()
if a.known:
    import importlib.util
    spec = importlib.util.spec_from_file_location('vendor_known', a.known)
    known = importlib.util.module_from_spec(spec); spec.loader.exec_module(known)
if not a.only:
    a.only = getattr(known, 'ONLY', r'^L(miui/|com/miui/|com/xiaomi/|android/.*/Miui|android/.*\$Miui|android/miui/|android/os/spc/|com/android/internal/app/Miui)')
HEADER = getattr(known, 'HEADER', 'Generated stand-in (.github/scripts/xiaomi-standins): names and signatures {app} uses, '
                 'answering "absent" off HyperOS (no Xiaomi code).')
# Optional vendor features, off unless the known file turns them on (honor-standins does): stand-ins that extend an
# Android SDK class (by name rule or as a framework widget inflated from the app's XML) leave the SDK's own members
# alone and call its constructors.
SDK_BASES = getattr(known, 'SDK_BASES', False)
def runtime_refs(code):
    # known bodies may name the app's runtime package as RUNTIME (e.g. RUNTIME.Magic.prop(...))
    return code.replace('RUNTIME.', a.runtime + '.') if code and a.runtime else code

u = json.load(open(os.path.join(a.work, 'usage.json')))
abstract = json.load(open(os.path.join(a.work, 'abstract.json')))
only = re.compile(a.only)
classes = {c: d for c, d in u['classes'].items() if only.search(c)}
inflated = set()
if SDK_BASES and a.apk:
    # Framework classes the app only names in its XML (layouts, preferences): <com.vendor.Widget>, <view class=...>
    import zipfile, struct
    def axml_names(data):
        out = set()
        if len(data) < 8 or struct.unpack_from('<H', data, 0)[0] != 0x0003: return out
        pos, strings = struct.unpack_from('<H', data, 2)[0], []
        while pos + 8 <= len(data):
            ctype, hsize, csize = struct.unpack_from('<HHI', data, pos)
            if csize < 8: break
            if ctype == 0x0001:
                count, _, flags, sstart = struct.unpack_from('<IIII', data, pos + 8)
                utf8 = flags & 0x100
                for i in range(count):
                    o = pos + sstart + struct.unpack_from('<I', data, pos + hsize + 4 * i)[0]
                    if utf8:
                        n = data[o]; o += 2 if n & 0x80 else 1
                        n = data[o]; o += 1
                        if n & 0x80: n = ((n & 0x7f) << 8) | data[o]; o += 1
                        strings.append(data[o:o + n].decode('utf-8', 'replace'))
                    else:
                        n = struct.unpack_from('<H', data, o)[0]; o += 2
                        if n & 0x8000: n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', data, o)[0]; o += 2
                        strings.append(data[o:o + 2 * n].decode('utf-16-le', 'replace'))
            elif ctype == 0x0102:
                name = struct.unpack_from('<I', data, pos + 20)[0]
                if name < len(strings): out.add(strings[name])
                astart, asize, acount = struct.unpack_from('<HHH', data, pos + 24)
                for k in range(acount):
                    q = pos + 16 + astart + k * asize
                    an, raw = struct.unpack_from('<I', data, q + 4)[0], struct.unpack_from('<I', data, q + 8)[0]
                    if an < len(strings) and strings[an] == 'class' and raw < len(strings): out.add(strings[raw])
            pos += csize
        return out
    def dex_classes(data):
        _, soff, _, toff = struct.unpack_from('<IIII', data, 0x38)
        cdefs, coff = struct.unpack_from('<II', data, 0x60)
        def string(i):
            o = struct.unpack_from('<I', data, soff + 4 * i)[0]
            while data[o] & 0x80: o += 1
            o += 1
            return data[o:data.index(0, o)].decode('utf-8', 'replace')
        return {string(struct.unpack_from('<I', data, toff + 4 * struct.unpack_from('<I', data, coff + 32 * k)[0])[0]) for k in range(cdefs)}
    z = zipfile.ZipFile(a.apk)
    own = set()
    for n in z.namelist():
        if re.match(r'classes\d*\.dex$', n): own |= dex_classes(z.read(n))
    # framework widgets only (a layout may also name classes R8 removed from the app or its libraries: dead layouts)
    only_re = re.compile(getattr(known, 'INFLATED_ONLY', a.only))
    for n in z.namelist():
        if not (n.startswith('res/') and n.endswith('.xml')): continue
        for name in axml_names(z.read(n)):
            if not re.match(r'^[a-z][\w]*(\.[\w$]+)+$', name): continue
            t = 'L' + name.replace('.', '/') + ';'
            if t in own or not only_re.search(t): continue
            inflated.add(t)
            classes.setdefault(t, {'methods': {}, 'fields': {}, 'extendedBy': [], 'implementedBy': [], 'typeUse': 0,
                                   'newInstance': True, 'caught': False, 'thrown': False, 'outerOnPhone': False})
missing = set(classes)
for c, fs in getattr(known, 'REFLECTED', {}).items():
    for f in fs:
        if c in classes: classes[c]['fields'].setdefault(f, ['sget'])
PRIM = {'Z': 'boolean', 'B': 'byte', 'S': 'short', 'C': 'char', 'I': 'int', 'J': 'long', 'F': 'float', 'D': 'double', 'V': 'void'}
skipped, notes = [], []

def sdk_classes():
    jar = a.sdk
    if not jar:
        home = os.environ.get('ANDROID_HOME') or os.path.expanduser('~/android-sdk')
        plats = sorted((d for d in os.listdir(os.path.join(home, 'platforms')) if d.startswith('android-')),
                       key=lambda d: int(re.sub(r'\D', '', d) or 0))
        jar = os.path.join(home, 'platforms', plats[-1], 'android.jar')
    import zipfile
    return {'L' + n[:-6] + ';' for n in zipfile.ZipFile(jar).namelist() if n.endswith('.class')}
SDK = sdk_classes()

SDK_JAR = None
_sdk_cache = {}
def sdk_class(t):
    """(superclass, {member key: access}) of an SDK class from android.jar's class file, or None."""
    global SDK_JAR
    if t in _sdk_cache: return _sdk_cache[t]
    if t not in SDK: return None
    import zipfile, struct
    if SDK_JAR is None:
        jar = a.sdk
        if not jar:
            home = os.environ.get('ANDROID_HOME') or os.path.expanduser('~/android-sdk')
            plats = sorted((d for d in os.listdir(os.path.join(home, 'platforms')) if d.startswith('android-')),
                           key=lambda d: int(re.sub(r'\D', '', d) or 0))
            jar = os.path.join(home, 'platforms', plats[-1], 'android.jar')
        SDK_JAR = zipfile.ZipFile(jar)
    d = SDK_JAR.read(t[1:-1] + '.class')
    n, pos, pool = struct.unpack_from('>H', d, 8)[0], 10, {}
    i = 1
    while i < n:
        tag = d[pos]
        if tag == 1:
            ln = struct.unpack_from('>H', d, pos + 1)[0]; pool[i] = d[pos + 3:pos + 3 + ln].decode('utf-8', 'replace'); pos += 3 + ln
        elif tag == 7: pool[i] = ('class', struct.unpack_from('>H', d, pos + 1)[0]); pos += 3
        elif tag in (5, 6): pos += 9; i += 1
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): pos += 5
        elif tag == 15: pos += 4
        elif tag in (8, 16, 19, 20): pos += 3
        else: raise ValueError(f'class file tag {tag} in {t}')
        i += 1
    sup_i = struct.unpack_from('>H', d, pos + 4)[0]
    sup = 'L' + pool[pool[sup_i][1]] + ';' if sup_i else None
    ni = struct.unpack_from('>H', d, pos + 6)[0]; pos += 8 + 2 * ni
    members = {}
    for kind in ('field', 'method'):
        cnt = struct.unpack_from('>H', d, pos)[0]; pos += 2
        for _ in range(cnt):
            acc, ni_, di, ac = struct.unpack_from('>HHHH', d, pos); pos += 8
            for _ in range(ac): pos += 6 + struct.unpack_from('>I', d, pos + 2)[0]
            if acc & 0x0005:  # public or protected: visible to a stand-in subclass
                members[(pool[ni_] + ':' + pool[di]) if kind == 'field' else (pool[ni_] + pool[di])] = acc
    _sdk_cache[t] = (sup, members)
    return _sdk_cache[t]

def sdk_members(t):
    """Every public/protected member key an SDK class has, its superclasses' included."""
    out, seen = {}, set()
    while t and t not in seen:
        seen.add(t); info = sdk_class(t)
        if not info: break
        for k, v in info[1].items(): out.setdefault(k, v)
        t = info[0]
    return out

def sdk_ctors(t):
    info = sdk_class(t)
    return [k[len('<init>'):] for k in (info[1] if info else {}) if k.startswith('<init>(')]

def split_params(s):
    out, i = [], 0
    while i < len(s):
        j = i
        while s[j] == '[': j += 1
        if s[j] == 'L': j = s.index(';', j)
        out.append(s[i:j + 1]); i = j + 1
    return out

# Kotlin members may carry names Java source cannot (HeyTap's DefValueUtilKt.default): skipped and listed
JAVA_KEYWORDS = {'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char', 'class', 'const', 'continue',
                 'default', 'do', 'double', 'else', 'enum', 'extends', 'final', 'finally', 'float', 'for', 'goto', 'if',
                 'implements', 'import', 'instanceof', 'int', 'interface', 'long', 'native', 'new', 'package', 'private',
                 'protected', 'public', 'return', 'short', 'static', 'strictfp', 'super', 'switch', 'synchronized',
                 'this', 'throw', 'throws', 'transient', 'try', 'void', 'volatile', 'while', 'true', 'false', 'null'}

def compilable(t):
    # the phone's hidden classes (not in the SDK) and the app's own classes can't be named in the extension's source
    b = t.lstrip('[')
    return b in PRIM or b in missing or b in SDK

def jtype(t):
    dims = len(t) - len(t.lstrip('[')); b = t.lstrip('[')
    if b in PRIM: name = PRIM[b]
    else:
        name = b[1:-1].replace('/', '.')
        # a stand-in nested in a phone class is a top-level class named Outer$Inner
        name = name if classes.get(b, {}).get('outerOnPhone') else name.replace('$', '.')
    return name + '[]' * dims

def outer_of(c): return c[:c.rindex('$')] + ';' if '$' in c.rsplit('/', 1)[1] else None

# --- what each stand-in extends -------------------------------------------------------------------------------
TAG_BASE = {'activity': 'Landroid/app/Activity;', 'activity-alias': None, 'service': 'Landroid/app/Service;',
            'receiver': 'Landroid/content/BroadcastReceiver;', 'provider': 'Landroid/content/ContentProvider;',
            'application': 'Landroid/app/Application;'}
base = dict(known.SUPERS)
if a.apk:
    xml = subprocess.run(['aapt2', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', a.apk], capture_output=True, text=True).stdout
    pkg = re.search(r'package="([^"]+)"', xml).group(1)
    tag = None
    for line in xml.splitlines():
        m = re.match(r'\s*E: ([\w-]+)', line)
        if m: tag = m.group(1); continue
        m = re.match(r'\s*A: \S*android:(name|backupAgent)\([^)]*\)="([^"]+)"', line)
        if not m or tag not in TAG_BASE: continue
        want = 'Landroid/app/backup/BackupAgent;' if m.group(1) == 'backupAgent' else TAG_BASE[tag]
        if not want: continue
        n = m.group(2); n = pkg + n if n.startswith('.') else n
        leaf = u['leaves'].get('L' + n.replace('.', '/') + ';')
        if leaf in missing and base.get(leaf, want) == want: base[leaf] = want
        elif leaf in missing: notes.append(f'{leaf}: manifest wants {want}, kept {base[leaf]}')
if a.extends:
    for line in open(a.extends):
        if line.strip() and not line.startswith('#'): c, s = line.split(); base[c] = s
for c, d in classes.items():
    if c not in base and (d.get('caught') or d.get('thrown') or re.search(r'(Exception|Error);$', c)):
        base[c] = 'Ljava/lang/Exception;'
if SDK_BASES:
    # A vendor class named after an Android class is that class's subclass (framework widgets, dialogs, preferences)
    for c in sorted(classes):
        if c in base: continue
        for rule, targets in getattr(known, 'SUPER_RULES', []):
            m = re.match(rule, c)
            hit = m and next((m.expand(x) for x in targets if m.expand(x) in SDK), None)
            if hit: base[c] = hit; break
        if c in inflated and c not in base: notes.append(f'{c}: inflated from XML but no Android base found')

# --- defaults ------------------------------------------------------------------------------------------------
FACTORY = re.compile(r'^(build|create\w*|newInstance|getInstance|obtain|clone|copy)$')
if getattr(known, 'FACTORIES', None):
    # a vendor's own factory names (ColorOS: getDefault, get(Class), getOplusPackageManager, ...), opt-in
    FACTORY = re.compile(FACTORY.pattern + '|' + known.FACTORIES)

def default_return(t, params, cls, static, mname):
    if t == 'V': return None
    b = t.lstrip('[')
    if mname.startswith('get') and len(params) >= 2 and params[-1] == t: return f'p{len(params) - 1}'
    if t.startswith('['): return f'new {jtype(b)}[0]' + '[]' * (len(t) - len(b) - 1)
    if t == 'Z': return 'false'
    if t in ('B', 'S', 'C'): return f'({PRIM[t]}) 0'
    if t == 'I': return '0'
    if t == 'J': return '0L'
    if t == 'F': return '0f'
    if t == 'D': return '0d'
    if t in ('Ljava/lang/String;', 'Ljava/lang/CharSequence;'): return '""'
    if t == cls and not static: return 'this'
    # builders and factories hand back an object, not null (lesson from the Samsung ports)
    if t in missing and t not in iface and (t == cls or FACTORY.match(mname)): return f'new {jtype(t)}()'
    if t in ('Ljava/util/List;', 'Ljava/util/Collection;'): return 'new java.util.ArrayList<>()'
    if t == 'Ljava/util/Map;': return 'new java.util.HashMap<>()'
    if t == 'Ljava/util/Set;': return 'new java.util.HashSet<>()'
    return 'null'

iface = set()
for c, d in classes.items():
    if d['implementedBy'] or any('interface' in k for k in d['methods'].values()): iface.add(c)
for c in missing:
    if c.endswith('$Stub;'): iface.add(c[:-len('$Stub;')] + ';')
needs_noarg = set()
for c, d in classes.items():
    for k in d['methods']:
        ret = k[k.index(')') + 1:]
        name = k[:k.index('(')]
        if ret in missing and ret not in iface and (ret == c or FACTORY.match(name)): needs_noarg.add(ret)

# --- the tree of classes per file ----------------------------------------------------------------------------
allc = set(missing)
for c in list(missing):
    if classes[c].get('outerOnPhone'): continue
    o = outer_of(c)
    while o and o not in allc:
        allc.add(o); o = outer_of(o)
children, tops = collections.defaultdict(list), []
for c in sorted(allc):
    o = outer_of(c)
    if o and o in allc and not classes.get(c, {}).get('outerOnPhone'): children[o].append(c)
    else: tops.append(c)

def abstract_impls(sup, have, pad):
    out = []
    for k in abstract.get(sup, []):
        name = k[:k.index('(')]; params = split_params(k[k.index('(') + 1:k.index(')')]); ret = k[k.index(')') + 1:]
        if name + '(' + ''.join(params) + ')' + ret in have: continue
        if not all(compilable(t) for t in params + [ret]): continue
        args = ', '.join(f'{jtype(t)} p{i}' for i, t in enumerate(params))
        r = default_return(ret, params, '', False, name)
        b = '' if r is None else f'\n{pad}    return {r};'
        out.append(f'{pad}@Override\n{pad}public {jtype(ret)} {name}({args}) {{{b}\n{pad}}}')
    return out

def ctor(c, simple, sup, params, pad):
    args = ', '.join(f'{jtype(t)} p{i}' for i, t in enumerate(params))
    call = ''
    # a known constructor body (e.g. the super call to a base that has no fitting constructor)
    known_body = known.METHODS.get(f'{c}-><init>(' + ''.join(params) + ')V')
    if known_body: return f'{pad}public {simple}({args}) {{\n{pad}    {runtime_refs(known_body)}\n{pad}}}'
    if SDK_BASES and sup in SDK:
        have = sdk_ctors(sup)
        want = '(' + ''.join(params) + ')V'
        if want in have: call = f'super({", ".join(f"p{i}" for i in range(len(params)))});'
        elif '()V' not in have:
            # the closest Android constructor: the longest one whose parameters start this one's
            best = max((h for h in have if want.startswith(h[:-2].rstrip(')')) and h != '()V'), key=len, default=None)
            if best:
                call = f'super({", ".join(f"p{i}" for i in range(len(split_params(best[1:-2]))))});'
            else:
                notes.append(f'{c}: no Android constructor of {sup} fits {want}, left out')
                return None
    if not call: return f'{pad}public {simple}({args}) {{\n{pad}}}'
    return f'{pad}public {simple}({args}) {{\n{pad}    {call}\n{pad}}}'

def body_for(c, indent, nested):
    d = classes.get(c, {'methods': {}, 'fields': {}})
    simple = c.rsplit('/', 1)[1][:-1]
    if not d.get('outerOnPhone'): simple = simple.rsplit('$', 1)[-1]
    is_if = c in iface
    mod = 'public ' + ('static ' if nested and not is_if else '')
    sup = base.get(c)
    if is_if: head = f'{mod}interface {simple}'
    elif c.endswith('$Stub;'): head = f'{mod}abstract class {simple} extends android.os.Binder implements {jtype(outer_of(c))}'
    elif sup: head = f'{mod}class {simple} extends {jtype(sup)}'
    else: head = f'{mod}class {simple}'
    lines, pad = [indent + head + ' {'], indent + '    '
    inherited = sdk_members(sup) if SDK_BASES and sup in SDK else {}
    for f, ops in sorted(d['fields'].items()):
        name, t = f.split(':', 1)
        if f in inherited: continue  # the Android base class has it: keep the real one
        if not compilable(t) or name in JAVA_KEYWORDS: skipped.append(c + '->' + f); continue
        st = any(o.startswith('s') for o in ops)
        key = f'{c}->{f}'
        init = runtime_refs(known.FIELDS.get(key))
        if init is None and is_if: init = default_return(t, [], c, True, '') or 'null'
        lines.append(f'{pad}public {"static " if st else ""}{jtype(t)} {name}{" = " + init if init else ""};')
    ctors, have = [], set()
    for k, kinds in sorted(d['methods'].items()):
        name = k[:k.index('(')]; params = split_params(k[k.index('(') + 1:k.index(')')]); ret = k[k.index(')') + 1:]
        if name != '<init>' and k in inherited: continue  # the Android base class has it: keep the real one
        if not all(compilable(t) for t in params + [ret]) or name in JAVA_KEYWORDS: skipped.append(c + '->' + k); continue
        args = ', '.join(f'{jtype(t)} p{i}' for i, t in enumerate(params))
        if name == '<init>':
            if not is_if and ctor(c, simple, sup, params, pad): ctors.append(ctor(c, simple, sup, params, pad))
            continue
        if name == '<clinit>': continue
        have.add(k)
        static = 'static' in kinds
        if is_if and not static: lines.append(f'{pad}{jtype(ret)} {name}({args});'); continue
        body = runtime_refs(known.METHODS.get(f'{c}->{k}'))
        if body is None:
            r = default_return(ret, params, c, static, name)
            body = '' if r is None else f'return {r};'
        b = f'\n{pad}    {body}' if body else ''
        lines.append(f'{pad}public {"static " if static else ""}{jtype(ret)} {name}({args}) {{{b}\n{pad}}}')
    if sup and not is_if: lines += abstract_impls(sup, have, pad)
    view = SDK_BASES and sup in SDK and '(Landroid/content/Context;Landroid/util/AttributeSet;)V' in sdk_ctors(sup)
    if SDK_BASES and (c in inflated or view) and sup in SDK and not is_if:
        # a View: LayoutInflater's constructors, as far as the Android base has them
        for sig in ('Landroid/content/Context;', 'Landroid/content/Context;Landroid/util/AttributeSet;',
                    'Landroid/content/Context;Landroid/util/AttributeSet;I', 'Landroid/content/Context;Landroid/util/AttributeSet;II'):
            if '(' + sig + ')V' in sdk_ctors(sup) and not any(f'public {simple}({", ".join(f"{jtype(t)} p{i}" for i, t in enumerate(split_params(sig)))})' in x for x in ctors):
                ctors.append(ctor(c, simple, sup, split_params(sig), pad))
    if not is_if and (c in needs_noarg or c.endswith('$Stub;')) and not any(f'public {simple}() {{' in x for x in ctors):
        if SDK_BASES and sup in SDK and '()V' not in sdk_ctors(sup): notes.append(f'{c}: no no-arg constructor (its base {sup} has none)')
        else: ctors.append(ctor(c, simple, sup, [], pad))
    if SDK_BASES and sup in SDK and not is_if and not ctors and sdk_ctors(sup) and '()V' not in sdk_ctors(sup):
        # Java needs some constructor that reaches the Android base: its shortest one
        shortest = min(sdk_ctors(sup), key=lambda h: (len(split_params(h[1:-2])), h))
        ctors.append(ctor(c, simple, sup, split_params(shortest[1:-2]), pad))
    lines[1:1] = ctors
    for ch in children[c]: lines += body_for(ch, pad, True)
    lines.append(indent + '}')
    return lines

n = 0
for top in tops:
    pkg, simple = top[1:-1].rsplit('/', 1)
    path = os.path.join(a.out, pkg, simple + '.java')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    src = [f'package {pkg.replace("/", ".")};', '', '/** ' + HEADER.format(app=a.app) + ' */']
    src += body_for(top, '', False)
    open(path, 'w').write('\n'.join(src) + '\n'); n += 1

if a.runtime:
    here = getattr(known, 'RUNTIME_DIR', None) or os.path.join(os.path.dirname(os.path.abspath(__file__)), 'runtime')
    dest = os.path.join(a.out, *a.runtime.split('.'))
    os.makedirs(dest, exist_ok=True)
    for f in sorted(os.listdir(here)):
        t = open(os.path.join(here, f)).read().replace('org.ungoogled.APP', a.runtime).replace('APP_NAME', a.app)
        target = os.path.join(dest, f)
        if f == 'Fallbacks.java' and os.path.exists(target): continue  # app-specific: never overwritten
        open(target, 'w').write(t)
print('files', n, 'skipped', len(skipped))
for s in skipped: print('  skip', s)
for s in notes: print('  note', s)
print('bases:', {c: s for c, s in base.items() if c in missing})
nested = [c for c in missing if classes[c].get('outerOnPhone')]
if nested: print('nested in phone classes (top-level Outer$Inner files):', nested)
if inflated: print('named in the app\'s XML only or also:', sorted(inflated))
