# hierarchy.py <apk> <reference.jar>... : supertypes for the stand-ins in usage.json, read from a reference library's
# class files (for Samsung's repackaged BouncyCastle, com.android.sec.org.bouncycastle.X <- org.bouncycastle.X in an
# old bcprov/bcpkix jar). Writes hierarchy.json, which gen.py uses: whether a stand-in is an interface, its superclass
# and its interfaces, kept only where each is itself a stand-in or a java.* / javax.* type (the verifier rejects app
# code that passes a stand-in where its supertype is expected, e.g. ECPoint$Fp for ECPoint). No library code is copied.
import json, struct, sys, zipfile

PREFIXES = [('Lcom/android/sec/org/bouncycastle/', 'org/bouncycastle/')]
usage = json.load(open('usage.json')); standins = set(usage)

def parse(data):
    """(is_interface, is_abstract, super, [interfaces]) from a class file."""
    o = 10; n = struct.unpack_from('>H', data, 8)[0]; pool = [None] * n; i = 1
    while i < n:
        tag = data[o]
        if tag == 1:
            ln = struct.unpack_from('>H', data, o + 1)[0]; pool[i] = data[o + 3:o + 3 + ln].decode('utf-8', 'replace'); o += 3 + ln
        elif tag == 7: pool[i] = ('class', struct.unpack_from('>H', data, o + 1)[0]); o += 3
        elif tag in (8, 16, 19, 20): o += 3
        elif tag == 15: o += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): o += 5
        elif tag in (5, 6): o += 9; i += 1
        else: raise ValueError('constant tag %d' % tag)
        i += 1
    access, this, sup, ni = struct.unpack_from('>HHHH', data, o)
    name = lambda k: pool[pool[k][1]] if k else None
    ifs = [name(struct.unpack_from('>H', data, o + 8 + 2 * k)[0]) for k in range(ni)]
    return bool(access & 0x200), bool(access & 0x400), name(sup), ifs

ref = {}
for jar in sys.argv[2:]:
    z = zipfile.ZipFile(jar)
    for n in z.namelist():
        if n.endswith('.class'): ref[n[:-6]] = z.read(n)

def to_ref(t):
    for ours, theirs in PREFIXES:
        if t.startswith(ours): return theirs + t[len(ours):-1]
    return None

def from_ref(n):
    for ours, theirs in PREFIXES:
        if n.startswith(theirs): return ours + n[len(theirs):] + ';'
    return 'L' + n + ';'

def usable(t):
    return t in standins or t.startswith(('Ljava/', 'Ljavax/'))

def supertypes(r):
    """The nearest usable superclass and every usable interface, through ancestors that aren't stand-ins themselves."""
    iface, _, sup, ifs = parse(ref[r])
    best, interfaces, todo, seen = None, [], list(ifs), set()
    while sup and sup != 'java/lang/Object':
        s = from_ref(sup)
        if usable(s): best = s; break
        if sup not in ref: dropped.append(r + ' super ' + s); break
        _, _, sup, more = parse(ref[sup]); todo += more
    while todo:
        i = todo.pop(0)
        if i in seen: continue
        seen.add(i); t = from_ref(i)
        if usable(t): interfaces.append(t)
        elif i in ref: todo += parse(ref[i])[3]
        else: dropped.append(r + ' implements ' + t)
    return iface, best, interfaces

out, dropped = {}, []
for c in sorted(standins):
    r = to_ref(c)
    if not r or r not in ref: continue
    iface, sup, ifs = supertypes(r)
    entry = {"interface": iface, "interfaces": ifs}
    if sup: entry["super"] = sup
    out[c] = entry
json.dump(out, open('hierarchy.json', 'w'), indent=1, sort_keys=True)
print('stand-ins with a reference class:', len(out), 'interfaces:', sum(e['interface'] for e in out.values()),
      'with super:', sum('super' in e for e in out.values()))
print('dropped (not a stand-in, not java.*):', len(dropped)); [print('  ', d) for d in dropped[:40]]
