# analyze_dex.py <apk> : analyze.py without baksmali -- reads the APK's dex files directly (pure Python, little memory)
# and writes the same usage.json for gen.py: how the app uses each class in samsung-ext.txt (methods with invoke kind,
# fields with get/put kind, subclasses, implementers, type uses, new-instance, caught).
import json, re, struct, sys, zipfile, collections

def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]; o += 1; r |= (x & 0x7f) << s
        if x < 0x80: return r, o
        s += 7

def sleb(b, o):
    r = s = 0
    while True:
        x = b[o]; o += 1; r |= (x & 0x7f) << s; s += 7
        if x < 0x80:
            if x & 0x40: r -= 1 << s
            return r, o

SZ = [1] * 256
for op, n in {0x02: 2, 0x03: 3, 0x05: 2, 0x06: 3, 0x08: 2, 0x09: 3, 0x13: 2, 0x14: 3, 0x15: 2, 0x16: 2, 0x17: 3, 0x18: 5,
              0x19: 2, 0x1a: 2, 0x1b: 3, 0x1c: 2, 0x1f: 2, 0x20: 2, 0x22: 2, 0x23: 2, 0x24: 3, 0x25: 3, 0x26: 3, 0x29: 2,
              0x2a: 3, 0x2b: 3, 0x2c: 3, 0xfa: 4, 0xfb: 4, 0xfc: 3, 0xfd: 3, 0xfe: 2, 0xff: 2}.items(): SZ[op] = n
for op in list(range(0x2d, 0x3e)) + list(range(0x44, 0x6e)) + list(range(0x90, 0xb0)) + list(range(0xd0, 0xe3)): SZ[op] = 2
for op in list(range(0x6e, 0x73)) + list(range(0x74, 0x79)): SZ[op] = 3
INVOKE = {0x6e: 'virtual', 0x6f: 'super', 0x70: 'direct', 0x71: 'static', 0x72: 'interface'}
for op, k in list(INVOKE.items()): INVOKE[op + 6] = k
FIELD = {}
for op in range(0x52, 0x59): FIELD[op] = 'iget'
for op in range(0x59, 0x60): FIELD[op] = 'iput'
for op in range(0x60, 0x67): FIELD[op] = 'sget'
for op in range(0x67, 0x6e): FIELD[op] = 'sput'
TYPE = {0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25}

missing = set(l.strip() for l in open('samsung-ext.txt') if l.strip())
info = collections.defaultdict(lambda: {"methods": collections.defaultdict(set), "fields": collections.defaultdict(set),
                                        "extendedBy": [], "implementedBy": [], "typeUse": 0, "newInstance": False, "caught": False})
z = zipfile.ZipFile(sys.argv[1])
for name in sorted(n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)):
    b = z.read(name)
    ns, so, nt, to, np_, po, nf, fo, nm, mo, nc, co = struct.unpack_from('<12I', b, 0x38)
    strs = {}
    def s(i):
        if i not in strs:
            off = struct.unpack_from('<I', b, so + 4 * i)[0]; _, p = uleb(b, off); strs[i] = b[p:b.index(b'\0', p)].decode('utf-8', 'replace')
        return strs[i]
    def t(i): return s(struct.unpack_from('<I', b, to + 4 * i)[0])
    def proto(i):
        _, rt, pl = struct.unpack_from('<III', b, po + 12 * i); args = ''
        if pl:
            n = struct.unpack_from('<I', b, pl)[0]; args = ''.join(t(struct.unpack_from('<H', b, pl + 4 + 2 * k)[0]) for k in range(n))
        return '(' + args + ')' + t(rt)
    for ci in range(nc):
        cls_i, _, sup, ifo, _, _, cdo, _ = struct.unpack_from('<8I', b, co + 32 * ci)
        cls = t(cls_i)
        if sup != 0xffffffff and t(sup) in missing: info[t(sup)]["extendedBy"].append(cls)
        if ifo:
            for k in range(struct.unpack_from('<I', b, ifo)[0]):
                it = t(struct.unpack_from('<H', b, ifo + 4 + 2 * k)[0])
                if it in missing: info[it]["implementedBy"].append(cls)
        if not cdo: continue
        o = cdo; sf, o = uleb(b, o); inf, o = uleb(b, o); dm, o = uleb(b, o); vm, o = uleb(b, o)
        for _ in range(sf + inf): _, o = uleb(b, o); _, o = uleb(b, o)
        for k in range(dm + vm):
            _, o = uleb(b, o); _, o = uleb(b, o); code, o = uleb(b, o)
            if not code: continue
            tries, = struct.unpack_from('<H', b, code + 6); n, = struct.unpack_from('<I', b, code + 12); base = code + 16; pc = 0
            while pc < n:
                u = struct.unpack_from('<H', b, base + 2 * pc)[0]; op = u & 0xff
                if op == 0 and u >> 8 in (1, 2, 3):
                    if u == 0x100: sz = struct.unpack_from('<H', b, base + 2 * pc + 2)[0] * 2 + 4
                    elif u == 0x200: sz = struct.unpack_from('<H', b, base + 2 * pc + 2)[0] * 4 + 2
                    else:
                        w = struct.unpack_from('<H', b, base + 2 * pc + 2)[0]; c = struct.unpack_from('<I', b, base + 2 * pc + 4)[0]; sz = (c * w + 1) // 2 + 4
                    pc += sz; continue
                if op in INVOKE:
                    mi = struct.unpack_from('<H', b, base + 2 * pc + 2)[0]
                    c, p, nn = struct.unpack_from('<HHI', b, mo + 8 * mi); owner = t(c)
                    if owner in missing: info[owner]["methods"][s(nn) + proto(p)].add(INVOKE[op])
                elif op in FIELD:
                    fi = struct.unpack_from('<H', b, base + 2 * pc + 2)[0]
                    c, ty, nn = struct.unpack_from('<HHI', b, fo + 8 * fi); owner = t(c)
                    if owner in missing: info[owner]["fields"][s(nn) + ':' + t(ty)].add(FIELD[op])
                elif op in TYPE:
                    ty = t(struct.unpack_from('<H', b, base + 2 * pc + 2)[0]).lstrip('[')
                    if ty in missing:
                        info[ty]["typeUse"] += 1
                        if op == 0x22: info[ty]["newInstance"] = True
                pc += SZ[op]
            if tries:
                hl = base + 2 * n + (2 if n % 2 else 0) + 8 * tries
                cnt, o2 = uleb(b, hl)
                for _ in range(cnt):
                    size, o2 = sleb(b, o2)
                    for _ in range(abs(size)):
                        ty, o2 = uleb(b, o2); _, o2 = uleb(b, o2)
                        if t(ty) in missing: info[t(ty)]["caught"] = True
                    if size <= 0: _, o2 = uleb(b, o2)
out = {c: {"methods": {k: sorted(v) for k, v in d["methods"].items()}, "fields": {k: sorted(v) for k, v in d["fields"].items()},
           "extendedBy": d["extendedBy"], "implementedBy": d["implementedBy"], "typeUse": d["typeUse"], "newInstance": d["newInstance"],
           **({"caught": True} if d["caught"] else {})} for c, d in info.items()}
for c in missing:
    out.setdefault(c, {"methods": {}, "fields": {}, "extendedBy": [], "implementedBy": [], "typeUse": 0, "newInstance": False})
json.dump(out, open('usage.json', 'w'), indent=1, sort_keys=True)
ext = {c: d for c, d in out.items() if d["extendedBy"] or d["implementedBy"]}
print("classes:", len(out), "methods:", sum(len(d["methods"]) for d in out.values()), "fields:", sum(len(d["fields"]) for d in out.values()))
print("extended/implemented:", {c: (len(d["extendedBy"]), len(d["implementedBy"])) for c, d in ext.items()})
