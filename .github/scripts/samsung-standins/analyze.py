import os, re, json, collections
missing = set(l.strip() for l in open('samsung-ext.txt') if l.strip())
inv = re.compile(r'invoke-(static|virtual|interface|direct|super)(?:/range)? \{[^}]*\}, (L[^;]+;)->([^(]+)\(([^)]*)\)(\S+)')
fld = re.compile(r'([si])(get|put)(?:-\w+)? [vp]\d+, (?:[vp]\d+, )?(L[^;]+;)->([^:]+):(\S+)')
typ = re.compile(r'(?:check-cast|instance-of|const-class|new-instance|new-array|filled-new-array)[^,]*, (\[*L[^;]+;)')
info = collections.defaultdict(lambda: {"methods": {}, "fields": {}, "extendedBy": [], "implementedBy": [], "typeUse": 0, "newInstance": False})
for root, _, files in os.walk('smali'):
    for f in files:
        p = os.path.join(root, f); cls = None
        for line in open(p, errors='replace'):
            s = line.strip()
            if s.startswith('.class'): cls = s.split()[-1]; continue
            if s.startswith('.super '):
                sup = s.split()[1]
                if sup in missing: info[sup]["extendedBy"].append(cls)
                continue
            if s.startswith('.implements '):
                it = s.split()[1]
                if it in missing: info[it]["implementedBy"].append(cls)
                continue
            m = inv.search(s)
            if m and m.group(2) in missing:
                kind, c, name, params, ret = m.groups()
                key = f"{name}({params}){ret}"
                k = info[c]["methods"].setdefault(key, set()); k.add(kind)
                continue
            m = fld.search(s)
            if m and m.group(3) in missing:
                st, op, c, name, t = m.groups()
                info[c]["fields"].setdefault(f"{name}:{t}", set()).add(st + op)
                continue
            m = typ.search(s)
            if m:
                t = m.group(1).lstrip('[')
                if t in missing:
                    info[t]["typeUse"] += 1
                    if s.startswith('new-instance'): info[t]["newInstance"] = True
out = {c: {"methods": {k: sorted(v) for k, v in d["methods"].items()}, "fields": {k: sorted(v) for k, v in d["fields"].items()},
           "extendedBy": d["extendedBy"], "implementedBy": d["implementedBy"], "typeUse": d["typeUse"], "newInstance": d["newInstance"]}
       for c, d in info.items()}
for c in missing:
    out.setdefault(c, {"methods": {}, "fields": {}, "extendedBy": [], "implementedBy": [], "typeUse": 0, "newInstance": False})
json.dump(out, open('usage.json', 'w'), indent=1, sort_keys=True)
ext = {c: d for c, d in out.items() if d["extendedBy"] or d["implementedBy"]}
print("classes:", len(out), "methods:", sum(len(d["methods"]) for d in out.values()), "fields:", sum(len(d["fields"]) for d in out.values()))
print("extended/implemented:", {c: (len(d["extendedBy"]), len(d["implementedBy"])) for c, d in ext.items()})
print("interfaces (invoke-interface):", [c for c, d in out.items() if any('interface' in k for k in d["methods"].values())])
