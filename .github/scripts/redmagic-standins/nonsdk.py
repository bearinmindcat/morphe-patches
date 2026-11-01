#!/usr/bin/env python3
# nonsdk.py <apk> [android.jar]: framework members (android.*, com.android.*, dalvik.*, java.*, libcore.*) the app's dex
# references that the public SDK (android.jar, public/protected members incl. inherited) does not have: "MEMBER
# Lowner;->name..." for a hidden member of an SDK class, "CLASS Lowner; name..." for a member of a hidden class.
# Default android.jar: the newest under ~/android-sdk/platforms.
import sys, zipfile, struct, re, os
apk = sys.argv[1]
def newest_jar():
    home = os.path.expanduser('~/android-sdk/platforms')
    plats = sorted((d for d in os.listdir(home) if d.startswith('android-')), key=lambda d: int(re.sub(r'\D', '', d) or 0))
    return os.path.join(home, plats[-1], 'android.jar')
jarp = sys.argv[2] if len(sys.argv) > 2 else newest_jar()
jar = zipfile.ZipFile(jarp); names = set(jar.namelist())
cache = {}
def cls(t):
    if t in cache: return cache[t]
    n = t[1:-1] + '.class'
    if n not in names: cache[t] = None; return None
    d = jar.read(n); cnt = struct.unpack_from('>H', d, 8)[0]; pos = 10; pool = {}; i = 1
    while i < cnt:
        tag = d[pos]
        if tag == 1:
            ln = struct.unpack_from('>H', d, pos+1)[0]; pool[i] = d[pos+3:pos+3+ln].decode('utf-8','replace'); pos += 3+ln
        elif tag == 7: pool[i] = ('c', struct.unpack_from('>H', d, pos+1)[0]); pos += 3
        elif tag in (5,6): pos += 9; i += 1
        elif tag in (3,4,9,10,11,12,17,18): pos += 5
        elif tag == 15: pos += 4
        elif tag in (8,16,19,20): pos += 3
        i += 1
    sup_i = struct.unpack_from('>H', d, pos+4)[0]
    sup = 'L' + pool[pool[sup_i][1]] + ';' if sup_i else None
    ni = struct.unpack_from('>H', d, pos+6)[0]
    ifs = ['L' + pool[pool[struct.unpack_from('>H', d, pos+8+2*k)[0]][1]] + ';' for k in range(ni)]
    pos += 8 + 2*ni; mem = set()
    for kind in ('f','m'):
        c = struct.unpack_from('>H', d, pos)[0]; pos += 2
        for _ in range(c):
            acc, a, b, ac = struct.unpack_from('>HHHH', d, pos); pos += 8
            for _ in range(ac): pos += 6 + struct.unpack_from('>I', d, pos+2)[0]
            if acc & 5: mem.add(pool[a] + (':' if kind == 'f' else '') + pool[b])
    cache[t] = (sup, ifs, mem); return cache[t]
def has(t, key, seen=None):
    seen = seen or set()
    if t in seen: return False
    seen.add(t); c = cls(t)
    if not c: return False
    if key in c[2]: return True
    return any(has(x, key, seen) for x in ([c[0]] if c[0] else []) + c[1])
def dex_refs(data):
    def u(o): return struct.unpack_from('<I', data, o)[0]
    ss, so, ts, to, ps, po, fs, fo, ms, mo = [u(0x38+4*k) for k in range(10)]
    def s(i):
        o = u(so+4*i)
        while data[o] & 0x80: o += 1
        o += 1; return data[o:data.index(0, o)].decode('utf-8','replace')
    def t(i): return s(u(to+4*i))
    def proto(i):
        b = po + 12*i; ret = t(u(b+4)); off = u(b+8); params = ''
        if off:
            n = u(off); params = ''.join(t(struct.unpack_from('<H', data, off+4+2*k)[0]) for k in range(n))
        return '(' + params + ')' + ret
    out = set()
    for k in range(fs):
        b = fo + 8*k; c, ty, n = struct.unpack_from('<HHI', data, b); out.add((t(c), s(n) + ':' + t(ty)))
    for k in range(ms):
        b = mo + 8*k; c, p, n = struct.unpack_from('<HHI', data, b); out.add((t(c), s(n) + proto(p)))
    return out
z = zipfile.ZipFile(apk); refs = set(); own = set()
for n in z.namelist():
    if re.match(r'classes\d*\.dex$', n):
        d = z.read(n); refs |= dex_refs(d)
        cdefs, coff = struct.unpack_from('<II', d, 0x60)
        so, to = struct.unpack_from('<I', d, 0x3c)[0], struct.unpack_from('<I', d, 0x44)[0]
        def s(i):
            o = struct.unpack_from('<I', d, so+4*i)[0]
            while d[o] & 0x80: o += 1
            o += 1; return d[o:d.index(0, o)].decode()
        for k in range(cdefs): own.add(s(struct.unpack_from('<I', d, to+4*struct.unpack_from('<I', d, coff+32*k)[0])[0]))
fw = re.compile(r'^L(android/|com/android/|dalvik/|java/|javax/|libcore/|org/apache/http/|sun/)')
for c, m in sorted(refs):
    if c in own or not fw.match(c) or c.startswith('Landroid/support/') or c.startswith('Landroidx/'): continue
    if cls(c) is None: print('CLASS', c, m); continue
    if m.startswith('<init>') and m in cls(c)[2]: continue
    if not has(c, m): print('MEMBER', c + '->' + m)
