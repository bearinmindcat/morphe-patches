#!/usr/bin/env python3
"""
merge.py <out dir> <work dir>...

Merges several outputs of ../xiaomi-standins/Missing.java (one per app version and phone it is meant to run on, e.g.
16.4.30 against Android 15's boot classpath and 15.6.24 against Android 11's) into one, so gen.py writes one set of
stand-ins that declares everything each version uses. On a phone that has one of those classes the phone's own loads
first, so a stand-in only ever answers where the class is missing.
"""
import json, os, sys

out, works = sys.argv[1], sys.argv[2:]
os.makedirs(out, exist_ok=True)
classes, leaves, abstract, members = {}, {}, {}, {}
for w in works:
    u = json.load(open(os.path.join(w, 'usage.json')))
    for c, d in u['classes'].items():
        m = classes.setdefault(c, {'methods': {}, 'fields': {}, 'extendedBy': [], 'implementedBy': [], 'typeUse': 0,
                                   'newInstance': False, 'caught': False, 'thrown': False, 'outerOnPhone': False})
        for k in ('methods', 'fields'):
            for name, kinds in d[k].items():
                m[k][name] = sorted(set(m[k].get(name, [])) | set(kinds))
        for k in ('extendedBy', 'implementedBy'):
            m[k] = sorted(set(m[k]) | set(d[k]))
        m['typeUse'] += d['typeUse']
        for k in ('newInstance', 'caught', 'thrown', 'outerOnPhone'):
            m[k] = m[k] or d[k]
    for c, s in u['leaves'].items():
        leaves.setdefault(c, s)
    for c, ms in json.load(open(os.path.join(w, 'abstract.json'))).items():
        abstract[c] = sorted(set(abstract.get(c, [])) | set(ms))
    for line in open(os.path.join(w, 'members.txt')):
        k, n = line.rstrip('\n').rsplit('\t', 1)
        members[k] = members.get(k, 0) + int(n)

with open(os.path.join(out, 'usage.json'), 'w') as f:
    json.dump({'classes': dict(sorted(classes.items())), 'leaves': dict(sorted(leaves.items()))}, f)
with open(os.path.join(out, 'abstract.json'), 'w') as f:
    json.dump(abstract, f)
with open(os.path.join(out, 'types.txt'), 'w') as f:
    f.writelines(c + '\n' for c in sorted(classes))
with open(os.path.join(out, 'members.txt'), 'w') as f:
    f.writelines(f'{k}\t{n}\n' for k, n in sorted(members.items()))
print(len(works), 'inputs:', len(classes), 'missing classes,', len(members), 'missing members')
