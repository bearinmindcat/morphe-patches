import json, re, os, sys, collections
u = json.load(open('usage.json')); missing = set(u)
OUT = sys.argv[1]
APP = sys.argv[2] if len(sys.argv) > 2 else 'Samsung Notes'
PRIM = {'Z':'boolean','B':'byte','S':'short','C':'char','I':'int','J':'long','F':'float','D':'double','V':'void'}
OK_PREFIX = ('Ljava/', 'Landroid/', 'Ldalvik/', 'Lorg/xml/', 'Lorg/json/', 'Ljavax/', 'Lorg/w3c/')
skipped = []

def split_params(s):
    out, i = [], 0
    while i < len(s):
        j = i
        while s[j] == '[': j += 1
        if s[j] == 'L': j = s.index(';', j)
        out.append(s[i:j+1]); i = j + 1
    return out

def compilable(t):
    b = t.lstrip('[')
    return b in PRIM or b in missing or b.startswith(OK_PREFIX)

def jtype(t):
    dims = len(t) - len(t.lstrip('[')); b = t.lstrip('[')
    name = PRIM[b] if b in PRIM else b[1:-1].replace('/', '.').replace('$', '.')
    return name + '[]' * dims

def default_return(t, params, cls, static, mname):
    if t == 'V': return None
    b = t.lstrip('[')
    # "get...(key, default)": answer the caller's own default
    if mname.startswith('get') and len(params) >= 2 and params[-1] == t: return f'p{len(params)-1}'
    if t.startswith('['): return f'new {jtype(b)}[0]' + '[]' * (len(t) - len(b) - 1)
    if t == 'Z': return 'false'
    if t in ('B', 'S', 'C'): return f'({PRIM[t]}) 0'
    if t in ('I',): return '0'
    if t == 'J': return '0L'
    if t == 'F': return '0f'
    if t == 'D': return '0d'
    if t == 'Ljava/lang/String;': return '""'
    if t == 'Ljava/lang/CharSequence;': return '""'
    if t == cls:
        if not static: return 'this'
        # A stand-in whose constructors refuse (see SUPER_ARGS) can't make one.
        return 'null' if super_throws(cls) else 'new ' + jtype(cls) + '()'
    if t in ('Ljava/util/List;', 'Ljava/util/Collection;'): return 'new java.util.ArrayList<>()'
    if t == 'Ljava/util/Map;': return 'new java.util.HashMap<>()'
    if t == 'Ljava/util/Set;': return 'new java.util.HashSet<>()'
    return 'null'

# Supertypes from a reference library (hierarchy.py), where there is one: interface or class, superclass, interfaces.
hier = json.load(open('hierarchy.json')) if os.path.exists('hierarchy.json') else {}
# JDK superclasses without a no-arg constructor a stand-in can call: what its constructors pass instead. A stand-in for
# a class Samsung's phones have can't be made off One UI anyway, so these constructors refuse.
SUPER_ARGS = {
    'Ljava/security/cert/PKIXParameters;': 'refuse()',
    'Ljava/io/FilterInputStream;': 'null',
}
# Checked exceptions those superclass constructors declare.
SUPER_THROWS = {'Ljava/security/cert/PKIXParameters;': ' throws java.security.InvalidAlgorithmParameterException'}
# Abstract methods of JDK superclasses a stand-in must implement.
SUPER_ABSTRACT = {'Ljava/io/OutputStream;': ['public void write(int p0) {\n}']}
# JDK interfaces a stand-in keeps (no abstract methods to implement).
JDK_MARKERS = {'Ljava/lang/Cloneable;', 'Ljava/io/Serializable;'}

# kinds
iface = set()
for c, d in u.items():
    if d['implementedBy'] or any('interface' in k for k in d['methods'].values()): iface.add(c)
for c in missing:
    if c.endswith('$Stub;'):
        outer = c[:-len('$Stub;')] + ';'
        iface.add(outer)

for c, h in hier.items():
    (iface.add if h['interface'] else iface.discard)(c)

# tree of classes per outermost
def outer_of(c): return c[:c.rindex('$')] + ';' if '$' in c.rsplit('/', 1)[1] else None
allc = set(missing)
for c in list(missing):
    o = outer_of(c)
    while o:
        allc.add(o); o = outer_of(o)
children = collections.defaultdict(list)
tops = []
for c in sorted(allc):
    o = outer_of(c)
    (children[o].append(c) if o else tops.append(c))

def super_throws(c):
    # Through stand-in superclasses too: their constructors declare it.
    while c in hier and hier[c].get('super'):
        c = hier[c]['super']
        if c in SUPER_THROWS: return SUPER_THROWS[c]
    return ''

def super_call(c, pad):
    arg = SUPER_ARGS.get(hier.get(c, {}).get('super'))
    return f'\n{pad}    super({arg});' if arg else ''

def body_for(c, indent, nested):
    d = u.get(c, {'methods': {}, 'fields': {}, 'extendedBy': []})
    simple = c.rsplit('/', 1)[1][:-1].rsplit('$', 1)[-1]
    is_if = c in iface
    lines = []
    mod = 'public ' + ('static ' if nested and not is_if else '')
    h = hier.get(c)
    if h and is_if:
        supers = [i for i in h['interfaces'] if i in missing or i in JDK_MARKERS]
        head = f'{mod}interface {simple}' + (' extends ' + ', '.join(jtype(i) for i in supers) if supers else '')
    elif h:
        sup = h.get('super')
        ifs = [i for i in h['interfaces'] if i in missing or i in JDK_MARKERS]
        head = f'{mod}class {simple}' + (f' extends {jtype(sup)}' if sup else '') + \
            (' implements ' + ', '.join(jtype(i) for i in ifs) if ifs else '')
    elif is_if:
        head = f'{mod}interface {simple}'
    elif c.endswith('$Stub;'):
        head = f'{mod}abstract class {simple} extends android.os.Binder implements {jtype(outer_of(c))}'
    elif d.get('caught'):
        # Named in a catch clause: the verifier wants a Throwable.
        head = f'{mod}class {simple} extends java.lang.Exception'
    else:
        head = f'{mod}class {simple}'
    lines.append(indent + head + ' {')
    pad = indent + '    '
    for f, ops in sorted(d['fields'].items()):
        name, t = f.split(':', 1)
        if not compilable(t): skipped.append(c + '->' + f); continue
        st = any(o.startswith('s') for o in ops)
        init = '' if not is_if else ' = ' + (default_return(t, [], c, True, '') or 'null')
        lines.append(f'{pad}public {"static " if st else ""}{jtype(t)} {name}{init};')
    ctors = []
    for k, kinds in sorted(d['methods'].items()):
        name = k[:k.index('(')]; params = split_params(k[k.index('(')+1:k.index(')')]); ret = k[k.index(')')+1:]
        if not all(compilable(t) for t in params + [ret]): skipped.append(c + '->' + k); continue
        args = ', '.join(f'{jtype(t)} p{i}' for i, t in enumerate(params))
        if name == '<init>':
            if not is_if: ctors.append(f'{pad}public {simple}({args}){super_throws(c)} {{{super_call(c, pad)}\n{pad}}}')
            continue
        if name == '<clinit>': continue
        static = 'static' in kinds
        if is_if and not static and c not in hier:
            lines.append(f'{pad}{jtype(ret)} {name}({args});'); continue
        if is_if and not static:
            # Default methods: stand-in classes that implement it (with a hierarchy) needn't repeat them.
            r = default_return(ret, params, c, False, name)
            r = 'null' if r == 'this' else r
            b = '' if r is None else f'\n{pad}    return {r};'
            lines.append(f'{pad}default {jtype(ret)} {name}({args}) {{{b}\n{pad}}}'); continue
        r = default_return(ret, params, c, static, name)
        b = '' if r is None else f'\n{pad}    return {r};'
        lines.append(f'{pad}public {"static " if static else ""}{jtype(ret)} {name}({args}) {{{b}\n{pad}}}')
    if not is_if:
        # With a hierarchy, subclasses' constructors call this one.
        needs_noarg = any(f'new {jtype(c)}()' in l for l in lines) or (c.endswith('$Stub;')) or c in hier
        if needs_noarg and not any(f'public {simple}() {{' in x for x in ctors):
            ctors.append(f'{pad}public {simple}(){super_throws(c)} {{{super_call(c, pad)}\n{pad}}}')
        for m in SUPER_ABSTRACT.get(hier.get(c, {}).get('super'), []):
            if m.split('(')[0].split()[-1] + '(' not in ''.join(lines):
                lines.append(pad + m.replace('\n', '\n' + pad))
        # Default methods two of its interfaces both have: a class must choose, so it gets its own.
        own = {k for k in d['methods']}
        seen = {}
        for i in hier.get(c, {}).get('interfaces', []):
            for k in u.get(i, {}).get('methods', {}):
                if k.startswith('<') or k in own or 'static' in u[i]['methods'][k]: continue
                seen.setdefault(k, set()).add(i)
        for k, owners in sorted(seen.items()):
            if len(owners) < 2: continue
            name = k[:k.index('(')]; params = split_params(k[k.index('(')+1:k.index(')')]); ret = k[k.index(')')+1:]
            if not all(compilable(t) for t in params + [ret]): continue
            args = ', '.join(f'{jtype(t)} p{i}' for i, t in enumerate(params))
            r = default_return(ret, params, c, False, name)
            b = '' if r is None else f'\n{pad}    return {r};'
            lines.append(f'{pad}public {jtype(ret)} {name}({args}) {{{b}\n{pad}}}')
        if hier.get(c, {}).get('super') in SUPER_ARGS and 'refuse()' in SUPER_ARGS[hier[c]['super']]:
            lines.append(f'{pad}private static java.util.Set<java.security.cert.TrustAnchor> refuse() {{\n{pad}    '
                         f'throw new UnsupportedOperationException("not on this phone");\n{pad}}}')
    lines[1:1] = ctors
    for ch in children[c]: lines += body_for(ch, pad, True)
    lines.append(indent + '}')
    return lines

n = 0
for top in tops:
    pkg, simple = top[1:-1].rsplit('/', 1)
    path = os.path.join(OUT, pkg, simple + '.java')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    src = [f'package {pkg.replace("/", ".")};', '',
           f'/** Generated stand-in (.github/scripts/samsung-standins): names and signatures {APP} uses, answering "absent" off One UI (no Samsung code). */']
    src += body_for(top, '', False)
    open(path, 'w').write('\n'.join(src) + '\n'); n += 1
print('files', n, 'skipped', len(skipped)); [print('  skip', s) for s in skipped]
