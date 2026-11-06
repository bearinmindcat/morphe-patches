#!/usr/bin/env python3
"""
hidden.py <boot dir> <app.apk> <stand-ins dir> <out .kt> [app name]

The Android members a system app references that an installed app cannot use on the phone the boot classpath comes
from (adb pull every entry of $BOOTCLASSPATH into <boot dir>, as for ../xiaomi-standins/Missing.java):

- hidden API it may not link to: the boot dex files' hidden-API flags say blocked or max-target-o..s (the app targets
  a newer SDK than any max-target list allows);
- members the phone does not have (newer Android, or ColorOS's additions to Android classes);
- members of Android classes the phone does not have, where the extension carries no stand-in that declares them
  (gen.py skips members whose signature names a hidden class);
- calls on system services' binder interfaces (IActivityManager, IWindowManager, ...), which an app may make but which
  nearly all need a permission only the system holds (Members answers a SecurityException with "absent").

References through the app's own classes count too: `invoke-virtual Lcom/app/MyView;->hiddenViewMethod()` resolves to
View's hidden member and is refused the same way. Members of the app's own classes, class initializers and members of
classes outside Android's namespaces (optional libraries the app handles itself) are left alone.

Writes <out .kt> (package taken from its existing first line, else org.ungoogled.patches.coloroslauncher):
HIDDEN_MEMBERS ("Lowner;->name(params)return" / "Lowner;->name:type") and HIDDEN_CONSTRUCTORS ("Lowner;-><init>(...)V"),
which the patch sends through the extension's Members class (reflection where the phone allows, "absent" elsewhere).
"""
import os, re, struct, sys, zipfile, collections

def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]; o += 1; r |= (x & 0x7f) << s; s += 7
        if x < 0x80: return r, o

class Dex:
    def __init__(self, b):
        self.b = b
        (self.ssz, self.soff, self.tsz, self.toff, self.psz, self.poff, self.fsz, self.foff, self.msz, self.moff,
         self.csz, self.coff) = struct.unpack_from('<12I', b, 0x38)
        self._s = {}
        self.types = [self.s(struct.unpack_from('<I', b, self.toff + 4 * i)[0]) for i in range(self.tsz)]
        self._p = {}

    def s(self, i):
        v = self._s.get(i)
        if v is None:
            o = struct.unpack_from('<I', self.b, self.soff + 4 * i)[0]; _, o = uleb(self.b, o)
            v = self.b[o:self.b.index(0, o)].decode('utf-8', 'replace'); self._s[i] = v
        return v

    def proto(self, i):
        v = self._p.get(i)
        if v is None:
            _, rt, po = struct.unpack_from('<III', self.b, self.poff + 12 * i); params = ''
            if po:
                n = struct.unpack_from('<I', self.b, po)[0]
                params = ''.join(self.types[struct.unpack_from('<H', self.b, po + 4 + 2 * j)[0]] for j in range(n))
            v = '(' + params + ')' + self.types[rt]; self._p[i] = v
        return v

    def method(self, i):
        c, p, n = struct.unpack_from('<HHI', self.b, self.moff + 8 * i)
        return self.types[c], self.s(n) + self.proto(p)

    def field(self, i):
        c, t, n = struct.unpack_from('<HHI', self.b, self.foff + 8 * i)
        return self.types[c], self.s(n) + ':' + self.types[t]

    def classes(self):
        """(type, superclass, interfaces, class_data offset, index)"""
        for ci in range(self.csz):
            cls, _, sup, itf, _, _, cdo, _ = struct.unpack_from('<8I', self.b, self.coff + 32 * ci)
            its = []
            if itf:
                n = struct.unpack_from('<I', self.b, itf)[0]
                its = [self.types[struct.unpack_from('<H', self.b, itf + 4 + 2 * j)[0]] for j in range(n)]
            yield self.types[cls], (self.types[sup] if sup != 0xffffffff else None), its, cdo, ci

    def members(self, cdo):
        """member keys a class defines, in class_data order (static fields, instance fields, direct, virtual)"""
        out = []
        if not cdo: return out
        o = cdo; nsf, o = uleb(self.b, o); nif, o = uleb(self.b, o); ndm, o = uleb(self.b, o); nvm, o = uleb(self.b, o)
        for count, kind in ((nsf, 'f'), (nif, 'f'), (ndm, 'm'), (nvm, 'm')):
            idx = 0
            for _ in range(count):
                d, o = uleb(self.b, o); idx += d; _, o = uleb(self.b, o)
                if kind == 'm': _, o = uleb(self.b, o)
                out.append(self.field(idx)[1] if kind == 'f' else self.method(idx)[1])
        return out

    def hiddenapi(self):
        mapoff = struct.unpack_from('<I', self.b, 0x34)[0]; cnt = struct.unpack_from('<I', self.b, mapoff)[0]
        for i in range(cnt):
            t, _, _, off = struct.unpack_from('<HHII', self.b, mapoff + 4 + 12 * i)
            if t == 0xF000: return off
        return None

def dexes(path):
    z = zipfile.ZipFile(path)
    for n in z.namelist():
        if re.match(r'classes\d*\.dex$', n): yield Dex(z.read(n))

# --- the phone: classes, hierarchy, members with their hidden-API flags ----------------------------------------
boot_dir, apk, standins, out = sys.argv[1:5]
# The app named in the output's header (other launcher ports reuse this script, e.g. Xperia Home).
app_name = sys.argv[5] if len(sys.argv) > 5 else 'OnePlus System Launcher'
SUP, FLAGS = {}, {}
for jar in sorted(os.listdir(boot_dir)):
    try: dl = list(dexes(os.path.join(boot_dir, jar)))
    except (zipfile.BadZipFile, OSError): continue
    for d in dl:
        h = d.hiddenapi()
        for t, sup, its, cdo, ci in d.classes():
            if t in SUP: continue
            SUP[t] = ([sup] if sup else []) + its
            keys = d.members(cdo)
            if h is None or not cdo:
                for k in keys: FLAGS[t + '->' + k] = 0
                continue
            rel = struct.unpack_from('<I', d.b, h + 4 + 4 * ci)[0]
            if not rel:
                for k in keys: FLAGS[t + '->' + k] = 0
                continue
            q = h + rel
            for k in keys:
                fl, q = uleb(d.b, q); FLAGS[t + '->' + k] = fl

def phone_flag(cls, member, seen=None):
    """the flags of the member a reference to cls->member resolves to on the phone, None if it has none"""
    seen = seen if seen is not None else set()
    if cls in seen: return None
    seen.add(cls)
    v = FLAGS.get(cls + '->' + member)
    if v is not None or member.startswith('<init>'): return v  # constructors are not inherited
    for s in SUP.get(cls, []):
        v = phone_flag(s, member, seen)
        if v is not None: return v
    return None

# --- the app: its classes and every member it references ---------------------------------------------------------
OWN, OWN_SUP, OWN_MEMBERS, REFS = set(), {}, collections.defaultdict(set), set()
for d in dexes(apk):
    for t, sup, its, cdo, _ in d.classes():
        OWN.add(t); OWN_SUP[t] = ([sup] if sup else []) + its
        OWN_MEMBERS[t].update(d.members(cdo))
    for i in range(d.msz): REFS.add(d.method(i))
    for i in range(d.fsz): REFS.add(d.field(i))
# Android classes the app carries its own copy of (AIDL stubs it was built with): the phone's copy loads first, so they
# are the phone's classes.
OWN -= set(SUP)

# --- the extension's stand-ins: classes and their declared members' types ------------------------------------------
STAND = set()
for root, _, files in os.walk(standins):
    for f in files:
        if not f.endswith('.java'): continue
        rel = os.path.relpath(os.path.join(root, f[:-5]), standins)
        if rel.startswith('org/ungoogled/'): continue
        top = 'L' + rel + ';'
        STAND.add(top)
        src = open(os.path.join(root, f)).read()
        for m in re.finditer(r'public (?:static )?(?:abstract )?(?:final )?(?:class|interface|enum) (\w+)', src):
            if m.group(1) != f[:-5].split('$')[-1]: STAND.add(top[:-1] + '$' + m.group(1) + ';')

home = os.environ.get('ANDROID_HOME') or os.path.expanduser('~/android-sdk')
plats = sorted((p for p in os.listdir(os.path.join(home, 'platforms')) if p.startswith('android-')),
               key=lambda p: int(re.sub(r'\D', '', p) or 0))
SDK = {'L' + n[:-6] + ';' for n in zipfile.ZipFile(os.path.join(home, 'platforms', plats[-1], 'android.jar')).namelist()
       if n.endswith('.class')}

def types_of(member):
    if ':' in member and '(' not in member: return [member.split(':', 1)[1]]
    params, ret = member[member.index('(') + 1:member.index(')')], member[member.index(')') + 1:]
    out, i = [ret], 0
    while i < len(params):
        j = i
        while params[j] == '[': j += 1
        if params[j] == 'L': j = params.index(';', j)
        out.append(params[i:j + 1]); i = j + 1
    return out

def compilable(t):
    b = t.lstrip('[')
    return len(b) == 1 or b in STAND or b in SDK

# System services' binder interfaces (IActivityManager, IWindowManager, ...): open to apps, but nearly every call a
# system launcher makes on them needs a permission only the system holds (a SecurityException for an installed app).
SERVICE = re.compile(r'^L.*/I[A-Z]\w*(Manager|Service|Session|Controller);$')

def binder(cls, seen=None):
    seen = seen if seen is not None else set()
    if cls == 'Landroid/os/IInterface;': return True
    if cls in seen: return False
    seen.add(cls)
    return any(binder(s, seen) for s in SUP.get(cls, []))

FRAMEWORK = re.compile(r'^L(android/|com/android/|java/|javax/|dalvik/|libcore/|sun/)')
ONE_UI = re.compile(r'^(sem[A-Z]|SEM_|mSem[A-Z])')

def resolve_own(cls, member, seen=None):
    """where a reference through the app's own class lands: 'own', a phone class, or None (nowhere)"""
    seen = seen if seen is not None else set()
    if cls in seen: return None
    seen.add(cls)
    if cls not in OWN: return cls
    if member in OWN_MEMBERS[cls]: return 'own'
    for s in OWN_SUP.get(cls, []):
        r = resolve_own(s, member, seen)
        if r == 'own': return r
        if r is not None and phone_flag(r, member) is not None: return r
    return None

members, ctors, counts = set(), set(), collections.Counter()
for cls, member in sorted(REFS):
    if member.startswith('<clinit>'): continue
    owner = cls
    if cls in OWN:
        if member.startswith('<init>'): continue
        r = resolve_own(cls, member)
        if r in (None, 'own'): continue
        owner = r
    if not FRAMEWORK.match(owner): continue
    if owner in SUP:
        v = phone_flag(owner, member)
        if v is None: why = 'missing'
        elif (v & 7) not in (0, 1): why = 'denied'
        elif SERVICE.match(owner) and binder(owner) and not member.startswith('<init>'): why = 'system service'
        else: continue
    elif owner in STAND:
        if all(compilable(t) for t in types_of(member)): continue
        why = 'stand-in lacks it'
    else:
        why = 'no such class'
    if owner.startswith('Landroid/') and ONE_UI.match(member): continue
    counts[why] += 1
    (ctors if member.startswith('<init>') else members).add(cls + '->' + member)

# --- app classes that extend an Android class through one of those constructors ------------------------------------
# A subclass's super(...) call cannot be redirected like a `new`. Where the Android class has an open constructor whose
# parameters begin the refused one's (SurfaceView's hidden 5-argument constructor -> its public 4-argument one), the
# super call takes that one; otherwise the class is rebased onto its nearest ancestor with an open no-argument
# constructor (Binder for an AIDL stub, Object for a listener), keeping the Android interfaces it implemented.
def ok(cls, member):
    v = FLAGS.get(cls + '->' + member)
    return v is not None and (v & 7) in (0, 1)

def params(sig):
    out, i = [], 0
    while i < len(sig):
        j = i
        while sig[j] == '[': j += 1
        if sig[j] == 'L': j = sig.index(';', j)
        out.append(sig[i:j + 1]); i = j + 1
    return out

extended = collections.defaultdict(set)
for c in OWN:
    for s_ in OWN_SUP.get(c, [])[:1]:
        if s_ in SUP: extended[s_].add(c)
substitutes, rebases = {}, {}
for key in sorted(ctors):
    cls, member = key.split('->', 1)
    if cls not in extended: continue
    want = params(member[member.index('(') + 1:member.index(')')])
    open_ctors = [k[len(cls) + 2:] for k in FLAGS if k.startswith(cls + '-><init>(') and ok(cls, k[len(cls) + 2:])]
    best = max((m for m in open_ctors if want[:len(params(m[m.index('(') + 1:m.index(')')]))] ==
                params(m[m.index('(') + 1:m.index(')')]) and m != member), key=len, default=None)
    if best is not None and best != '<init>()V':
        substitutes[key] = cls + '->' + best
        continue
    # Only binder stubs (callbacks handed to the system) and plain listeners are rebased: a subclass deeper in Android's
    # hierarchy (a TaskOrganizer is a WindowOrganizer) is passed around as its ancestors, which a rebased object no
    # longer is. Those stay as they are and fail only if made, which only the system's window shell does.
    if not (cls.endswith('$Stub;') or SUP.get(cls, [None])[0] == 'Ljava/lang/Object;'): continue
    base, interfaces, t = None, [], cls
    while t is not None:
        sups = SUP.get(t, [])
        t = sups[0] if sups else None
        if t is None: break
        if ok(t, '<init>()V'): base = t; break
    def all_ifaces(c, acc):
        for i in SUP.get(c, [])[1:]:
            if i not in acc: acc.append(i); all_ifaces(i, acc)
        sups = SUP.get(c, [])
        if sups and sups[0] != base: all_ifaces(sups[0], acc)
        return acc
    rebases[cls] = (base or 'Ljava/lang/Object;', [i for i in all_ifaces(cls, []) if FRAMEWORK.match(i)])

# Code that still names a rebased class (calls its methods on the rebased objects, passes them where the old class is
# expected, constructs it): the call goes through Members as well, with its references typed Object in the shim, so the
# rebased objects verify; Members answers "absent" for them (they are no longer that class).
def defined_in(cls, member, seen=None):
    seen = seen if seen is not None else set()
    if cls in seen: return None
    seen.add(cls)
    if (cls + '->' + member) in FLAGS: return cls
    if member.startswith('<init>'): return None
    for s_ in SUP.get(cls, []):
        r = defined_in(s_, member, seen)
        if r: return r
    return None

def above(cls, ancestor, seen=None):
    """whether ancestor is cls or one of its superclasses / interfaces"""
    seen = seen if seen is not None else set()
    if cls == ancestor: return True
    if cls in seen: return False
    seen.add(cls)
    return any(above(s_, ancestor, seen) for s_ in SUP.get(cls, []))

for cls, member in sorted(REFS):
    if member.startswith('<clinit>'): continue
    key = cls + '->' + member
    if key in members or key in ctors: continue
    owner = cls
    if cls in OWN:
        if member.startswith('<init>'): continue
        r = resolve_own(cls, member)
        if r in (None, 'own'): continue
        owner = r
    hit = False
    for old, (base, _) in rebases.items():
        if above(owner, old) or above(old, owner):
            d = defined_in(owner, member)
            if d is not None and above(old, d) and not above(base, d): hit = True
    if not hit and '(' in member:
        hit = any(t in rebases for t in types_of(member)[1:])
    if not hit: continue
    counts['rebased class'] += 1
    (ctors if member.startswith('<init>') else members).add(key)

def kt(s): return '"' + s.replace('\\', '\\\\').replace('$', '\\$').replace('"', '\\"') + '"'
pkg = 'org.ungoogled.patches.coloroslauncher'
if os.path.exists(out):
    first = open(out).readline()
    if first.startswith('package '): pkg = first.split()[1]
src = f'''package {pkg}

/*
 * Generated by .github/scripts/coloros-launcher/hidden.py (do not edit): the Android members {app_name}
 * references that an installed app cannot use on Android 15 -- hidden API it may not link to, members Android 15 does
 * not have, members of classes it does not have that no stand-in declares, calls on system services' binder interfaces
 * that need the system's permissions. Each goes through the extension's Members class, which uses it where the phone
 * allows and answers "absent" elsewhere.
 * {", ".join(f"{k}: {v}" for k, v in sorted(counts.items()))}
 */

internal val HIDDEN_MEMBERS = setOf(
''' + ''.join(f'    {kt(k)},\n' for k in sorted(members)) + ''')

/** Constructors of the same kind, called with new: Members.construct makes the object where it can (null otherwise). */
internal val HIDDEN_CONSTRUCTORS = setOf(
''' + ''.join(f'    {kt(k)},\n' for k in sorted(ctors)) + ''')

/** Super calls to such a constructor that take an open constructor of the same class with the first arguments. */
internal val SUPER_CONSTRUCTORS = mapOf(
''' + ''.join(f'    {kt(k)} to {kt(v)},\n' for k, v in sorted(substitutes.items())) + ''')

/**
 * Android classes the launcher extends through such a constructor, with the class each subclass is rebased onto and the
 * Android interfaces it keeps (see rebaseSubclasses).
 */
internal val HIDDEN_SUPERCLASSES = mapOf(
''' + ''.join(f'    {kt(k)} to ({kt(b)} to listOf({", ".join(kt(i) for i in its)})),\n' for k, (b, its) in sorted(rebases.items())) + ')\n'
open(out, 'w').write(src)
print(len(members), 'members,', len(ctors), 'constructors;', dict(counts), len(substitutes), 'super constructors,',
      len(rebases), 'rebased superclasses')
