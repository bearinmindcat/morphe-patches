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
ap.add_argument('--only', help='regex: only classes matching it (default: Xiaomi framework packages)',
                default=r'^L(miui/|com/miui/|com/xiaomi/|android/.*/Miui|android/.*\$Miui|android/miui/|com/android/internal/app/Miui)')
a = ap.parse_args()

u = json.load(open(os.path.join(a.work, 'usage.json')))
abstract = json.load(open(os.path.join(a.work, 'abstract.json')))
only = re.compile(a.only)
classes = {c: d for c, d in u['classes'].items() if only.search(c)}
missing = set(classes)
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

def split_params(s):
    out, i = [], 0
    while i < len(s):
        j = i
        while s[j] == '[': j += 1
        if s[j] == 'L': j = s.index(';', j)
        out.append(s[i:j + 1]); i = j + 1
    return out

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

# --- defaults ------------------------------------------------------------------------------------------------
FACTORY = re.compile(r'^(build|create\w*|newInstance|getInstance|obtain|clone|copy)$')

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
    for f, ops in sorted(d['fields'].items()):
        name, t = f.split(':', 1)
        if not compilable(t): skipped.append(c + '->' + f); continue
        st = any(o.startswith('s') for o in ops)
        key = f'{c}->{f}'
        init = known.FIELDS.get(key)
        if init is None and is_if: init = default_return(t, [], c, True, '') or 'null'
        lines.append(f'{pad}public {"static " if st else ""}{jtype(t)} {name}{" = " + init if init else ""};')
    ctors, have = [], set()
    for k, kinds in sorted(d['methods'].items()):
        name = k[:k.index('(')]; params = split_params(k[k.index('(') + 1:k.index(')')]); ret = k[k.index(')') + 1:]
        if not all(compilable(t) for t in params + [ret]): skipped.append(c + '->' + k); continue
        args = ', '.join(f'{jtype(t)} p{i}' for i, t in enumerate(params))
        if name == '<init>':
            if not is_if: ctors.append(f'{pad}public {simple}({args}) {{\n{pad}}}')
            continue
        if name == '<clinit>': continue
        have.add(k)
        static = 'static' in kinds
        if is_if and not static: lines.append(f'{pad}{jtype(ret)} {name}({args});'); continue
        body = known.METHODS.get(f'{c}->{k}')
        if body is None:
            r = default_return(ret, params, c, static, name)
            body = '' if r is None else f'return {r};'
        b = f'\n{pad}    {body}' if body else ''
        lines.append(f'{pad}public {"static " if static else ""}{jtype(ret)} {name}({args}) {{{b}\n{pad}}}')
    if sup and not is_if: lines += abstract_impls(sup, have, pad)
    if not is_if and (c in needs_noarg or c.endswith('$Stub;')) and not any(f'public {simple}() {{' in x for x in ctors):
        ctors.append(f'{pad}public {simple}() {{\n{pad}}}')
    lines[1:1] = ctors
    for ch in children[c]: lines += body_for(ch, pad, True)
    lines.append(indent + '}')
    return lines

n = 0
for top in tops:
    pkg, simple = top[1:-1].rsplit('/', 1)
    path = os.path.join(a.out, pkg, simple + '.java')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    src = [f'package {pkg.replace("/", ".")};', '',
           f'/** Generated stand-in (.github/scripts/xiaomi-standins): names and signatures {a.app} uses, answering "absent" off HyperOS (no Xiaomi code). */']
    src += body_for(top, '', False)
    open(path, 'w').write('\n'.join(src) + '\n'); n += 1

if a.runtime:
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'runtime')
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
