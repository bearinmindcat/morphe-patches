#!/usr/bin/env python3
"""
Lists the Android methods and fields newer than Android 13 (API 33) that apps call, for the shared
redirectNewerAndroidMembers patch helper (patches/.../samsung/NewerAndroid.kt), and writes them as
patches/.../samsung/NewerAndroidMembers.kt.

  python3 newer_members.py <api-versions.xml> <out.kt> <apk>...

api-versions.xml comes with the Android SDK (platforms/android-36/data/api-versions.xml). Every invoke (not
invoke-super, not constructors) and every field read in the apps' dex is resolved through the app's own classes to
the first class the app doesn't define (the class the patch sees at a call site); when the member's "since" level
there is above 33, "<since> Lowner;->name(params)return" (or "Lowner;->name:type") goes into the list. Only the
class as written in the dex is listed, so the patch matches without knowing Android's class tree.
"""
import re
import struct
import sys
import zipfile
import xml.etree.ElementTree as ET

LEVEL = 33


def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7f) << s
        if x < 0x80:
            return r, o
        s += 7


SZ = [1] * 256
for op, n in {0x02: 2, 0x03: 3, 0x05: 2, 0x06: 3, 0x08: 2, 0x09: 3, 0x13: 2, 0x14: 3, 0x15: 2, 0x16: 2, 0x17: 3, 0x18: 5,
              0x19: 2, 0x1a: 2, 0x1b: 3, 0x1c: 2, 0x1f: 2, 0x20: 2, 0x22: 2, 0x23: 2, 0x24: 3, 0x25: 3, 0x26: 3, 0x29: 2,
              0x2a: 3, 0x2b: 3, 0x2c: 3, 0xfa: 4, 0xfb: 4, 0xfc: 3, 0xfd: 3, 0xfe: 2, 0xff: 2}.items():
    SZ[op] = n
for op in list(range(0x2d, 0x3e)) + list(range(0x44, 0x6e)) + list(range(0x90, 0xb0)) + list(range(0xd0, 0xe3)):
    SZ[op] = 2
for op in list(range(0x6e, 0x73)) + list(range(0x74, 0x79)):
    SZ[op] = 3
# invoke-virtual/static/interface (+ range); not invoke-super (0x6f/0x75) or invoke-direct (0x70/0x76)
INVOKE = {0x6e, 0x71, 0x72, 0x74, 0x77, 0x78}
FIELD_READ = set(range(0x52, 0x59)) | set(range(0x60, 0x67))  # iget*, sget*


class Dex:
    def __init__(self, b):
        self.b = b
        (self.ns, self.so, self.nt, self.to, self.np, self.po, self.nf, self.fo, self.nm, self.mo, self.nc,
         self.co) = struct.unpack_from('<12I', b, 0x38)
        self.sc = {}

    def s(self, i):
        r = self.sc.get(i)
        if r is None:
            off = struct.unpack_from('<I', self.b, self.so + 4 * i)[0]
            _, p = uleb(self.b, off)
            r = self.b[p:self.b.index(b'\0', p)].decode('utf-8', 'replace')
            self.sc[i] = r
        return r

    def t(self, i):
        return self.s(struct.unpack_from('<I', self.b, self.to + 4 * i)[0])

    def proto(self, i):
        _, rt, pl = struct.unpack_from('<III', self.b, self.po + 12 * i)
        args = ''
        if pl:
            n = struct.unpack_from('<I', self.b, pl)[0]
            args = ''.join(self.t(struct.unpack_from('<H', self.b, pl + 4 + 2 * k)[0]) for k in range(n))
        return '(' + args + ')' + self.t(rt)

    def mref(self, i):
        c, p, n = struct.unpack_from('<HHI', self.b, self.mo + 8 * i)
        return self.t(c), self.s(n) + self.proto(p)

    def fref(self, i):
        c, ty, n = struct.unpack_from('<HHI', self.b, self.fo + 8 * i)
        return self.t(c), self.s(n) + ':' + self.t(ty)


def load_api(path):
    api = {}
    for c in ET.parse(path).getroot().iter('class'):
        cs = int(c.get('since', '1'))
        d = {'since': cs, 'supers': [], 'm': {}, 'f': {}}
        for e in c:
            if e.tag in ('extends', 'implements'):
                d['supers'].append('L' + e.get('name') + ';')
            elif e.tag == 'method':
                d['m'][e.get('name')] = int(e.get('since', cs))
            elif e.tag == 'field':
                d['f'][e.get('name')] = int(e.get('since', cs))
        api['L' + c.get('name') + ';'] = d
    return api


def api_lookup(api, c, key, kind, seen=None):
    if seen is None:
        seen = set()
    if c in seen or c not in api:
        return None
    seen.add(c)
    tab = api[c]['m' if kind == 'm' else 'f']
    k = key if kind == 'm' else key.split(':')[0]
    if k in tab:
        return tab[k]
    for s in api[c]['supers']:
        r = api_lookup(api, s, key, kind, seen)
        if r is not None:
            return r
    if kind == 'm' and c != 'Ljava/lang/Object;' and 'Ljava/lang/Object;' not in seen:
        return api_lookup(api, 'Ljava/lang/Object;', key, kind, seen)
    return None


def scan(apk, api, out):
    z = zipfile.ZipFile(apk)
    names = sorted((n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)), key=lambda n: int(n[7:-4] or 1))
    ds = [Dex(z.read(n)) for n in names]
    app = {}
    for d in ds:
        b = d.b
        for ci in range(d.nc):
            cls_i, _, sup, _, _, _, cdo, _ = struct.unpack_from('<8I', b, d.co + 32 * ci)
            cls = d.t(cls_i)
            ms, fs = set(), set()
            if cdo:
                o = cdo
                sf, o = uleb(b, o); inf, o = uleb(b, o); dm, o = uleb(b, o); vm, o = uleb(b, o)
                for cnt in (sf, inf):
                    idx = 0
                    for _ in range(cnt):
                        x, o = uleb(b, o); _, o = uleb(b, o); idx += x
                        fs.add(d.fref(idx)[1].split(':')[0])
                for cnt in (dm, vm):
                    idx = 0
                    for _ in range(cnt):
                        x, o = uleb(b, o); _, o = uleb(b, o); _, o = uleb(b, o); idx += x
                        ms.add(d.mref(idx)[1])
            app.setdefault(cls, (d.t(sup) if sup != 0xffffffff else None, ms, fs))

    def first_external(owner, key, kind):
        c = owner
        while c in app:
            sup, ms, fs = app[c]
            if (key in ms) if kind == 'm' else (key.split(':')[0] in fs):
                return None
            c = sup
        return c

    for d in ds:
        b = d.b
        for ci in range(d.nc):
            cdo = struct.unpack_from('<8I', b, d.co + 32 * ci)[6]
            if not cdo:
                continue
            o = cdo
            sf, o = uleb(b, o); inf, o = uleb(b, o); dm, o = uleb(b, o); vm, o = uleb(b, o)
            for _ in range(sf + inf):
                _, o = uleb(b, o); _, o = uleb(b, o)
            for _ in range(dm + vm):
                _, o = uleb(b, o); _, o = uleb(b, o); code, o = uleb(b, o)
                if not code:
                    continue
                n, = struct.unpack_from('<I', b, code + 12)
                base = code + 16
                pc = 0
                while pc < n:
                    u = struct.unpack_from('<H', b, base + 2 * pc)[0]
                    op = u & 0xff
                    if op == 0 and u >> 8 in (1, 2, 3):
                        if u == 0x100:
                            sz = struct.unpack_from('<H', b, base + 2 * pc + 2)[0] * 2 + 4
                        elif u == 0x200:
                            sz = struct.unpack_from('<H', b, base + 2 * pc + 2)[0] * 4 + 2
                        else:
                            w = struct.unpack_from('<H', b, base + 2 * pc + 2)[0]
                            c = struct.unpack_from('<I', b, base + 2 * pc + 4)[0]
                            sz = (c * w + 1) // 2 + 4
                        pc += sz
                        continue
                    ref = None
                    if op in INVOKE:
                        ref = ('m',) + d.mref(struct.unpack_from('<H', b, base + 2 * pc + 2)[0])
                    elif op in FIELD_READ:
                        ref = ('f',) + d.fref(struct.unpack_from('<H', b, base + 2 * pc + 2)[0])
                    if ref and not ref[2].startswith('<'):
                        kind, owner, key = ref
                        if owner.startswith('L'):
                            e = first_external(owner, key, kind)
                            if e is not None:
                                since = api_lookup(api, e, key, kind)
                                if since is not None and since > LEVEL:
                                    out[e + '->' + key] = since
                    pc += SZ[op]


def main():
    api = load_api(sys.argv[1])
    out = {}
    for apk in sys.argv[3:]:
        scan(apk, api, out)
    lines = '\n'.join(f'{v} {k}' for k, v in sorted(out.items(), key=lambda x: (x[0])))
    kt = f'''package org.ungoogled.patches.samsung

// Generated by .github/scripts/android13/newer_members.py from api-versions.xml (android-36) and the apps patched
// for Android 13 -- do not edit by hand; regenerate with every app version added.

/** "<since> <member key>" for every Android member newer than Android 13 the listed apps call or read. */
internal val NEWER_ANDROID_MEMBERS: Map<String, Int> = MEMBERS.lineSequence().filter {{ it.isNotBlank() }}
    .associate {{ line -> line.substringAfter(' ') to line.substringBefore(' ').toInt() }}

private const val MEMBERS = """
{lines.replace('$', '${"$"}')}
"""
'''
    open(sys.argv[2], 'w').write(kt)
    print(f'{len(out)} members', file=sys.stderr)


if __name__ == '__main__':
    main()
