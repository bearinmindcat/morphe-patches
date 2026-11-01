#!/usr/bin/env python3
"""lgres.py <app.apk> <lge-res.apk>: the app's references to LG's framework resources (package "com.lge", id 0x02).

Off LG's ROM there is no com.lge resource package: a layout or style value "?com.lge:attr/x" (a theme attribute only
LG's Theme.LGE.Default defines) fails to inflate ("Failed to resolve attribute"), "@com.lge:dimen/x" has no resource
behind it, and an app style whose parent is an LG theme has no parent at all. Lists each such reference the app's XML
files and resource table use (binary XML scanned directly, the table through aapt2), with its name and what LG's
default theme (Theme.LGE.Default) answers, followed down to plain values per configuration (default, night, ...).
Prints Kotlin entries for patches/.../lg/LgResources.kt: plain values (colours with their night value, sizes), and
lists the rest (drawables, layouts, styles, colour state lists), which a port answers itself.
"""
import re, struct, subprocess, sys, zipfile, collections

app, fw = sys.argv[1], sys.argv[2]

def axml_refs(data):
    """(kind, id) of every attribute value in a binary XML that points into package 0x02."""
    out = []
    if len(data) < 8 or struct.unpack_from('<H', data, 0)[0] != 0x0003: return out
    pos = struct.unpack_from('<H', data, 2)[0]
    while pos + 8 <= len(data):
        ctype, hsize, csize = struct.unpack_from('<HHI', data, pos)
        if csize < 8: break
        if ctype == 0x0102:
            astart, asize, acount = struct.unpack_from('<HHH', data, pos + 24)
            for k in range(acount):
                q = pos + 16 + astart + k * asize
                dtype, value = data[q + 15], struct.unpack_from('<I', data, q + 16)[0]
                if dtype in (1, 2, 7, 8) and value >> 24 == 0x02: out.append(('?' if dtype in (2, 8) else '@', value))
        pos += csize
    return out

uses = collections.defaultdict(set)
z = zipfile.ZipFile(app)
for n in z.namelist():
    if n.startswith('res/') and n.endswith('.xml'):
        for kind, v in axml_refs(z.read(n)): uses[(kind, v)].add(n)
dump = subprocess.run(['aapt2', 'dump', 'resources', app], capture_output=True, text=True).stdout
res = None
for line in dump.splitlines():
    m = re.match(r'\s+resource (0x[0-9a-f]+) (\S+)', line)
    if m: res = m.group(2); continue
    for kind, v in re.findall(r'([?@])0x(02[0-9a-f]{6})', line): uses[(kind, int(v, 16))].add('resources.arsc ' + res)
    for v in re.findall(r'parent=0x(02[0-9a-f]{6})', line): uses[('@', int(v, 16))].add('resources.arsc ' + res + ' (parent)')

# the framework: names, values per configuration, Theme.LGE.Default's attribute values
fdump = subprocess.run(['aapt2', 'dump', 'resources', fw], capture_output=True, text=True).stdout
names, values, theme, cur, in_theme = {}, collections.defaultdict(dict), {}, None, False
for line in fdump.splitlines():
    m = re.match(r'\s+resource (0x[0-9a-f]+) (\S+)', line)
    if m:
        cur = int(m.group(1), 16); names[cur] = m.group(2); in_theme = m.group(2) == 'style/Theme.LGE.Default'; continue
    m = re.match(r'\s+\(([^)]*)\) (.+)$', line)
    if m and cur is not None and not in_theme:
        cfg = m.group(1)
        # configurations a phone can be in (not TV / watch / car / old API levels)
        if not re.search(r'television|watch|car|desk|mcc|v\d\d?$', cfg): values[cur][cfg] = m.group(2).strip()
    m = re.match(r'\s+\w*\((0x[0-9a-f]+)\)=(.+)$', line)
    if in_theme and m: theme[int(m.group(1), 16)] = m.group(2).strip()
by_name = {v: k for k, v in names.items()}

def follow(v, depth=0):
    """A framework value down to plain values: {config: value} or a description of what it is."""
    m = re.match(r'@(\w+/[\w.]+)$', v)
    if m and depth < 5 and m.group(1) in by_name:
        rid = by_name[m.group(1)]
        vals = values.get(rid, {})
        if not vals: return {'': v}
        out = {}
        for cfg, x in vals.items():
            sub = follow(x, depth + 1)
            for c2, y in sub.items(): out[(cfg + ' ' + c2).strip() if c2 else cfg] = y
        return out
    return {'': v}

plain, other = [], []
for (kind, v), where in sorted(uses.items(), key=lambda x: x[0][1]):
    key = f'{kind}0x{v:08x}'
    name = names.get(v, '?')
    if kind == '?':
        tv = theme.get(v)
        resolved = follow(tv) if tv else {'': '(not in Theme.LGE.Default)'}
    else:
        vals = values.get(v, {})
        resolved = {}
        for cfg, x in vals.items():
            for c2, y in follow(x).items(): resolved[(cfg + ' ' + c2).strip() if c2 else cfg] = y
    simple = all(re.match(r'^(#[0-9a-f]{6,8}|-?[\d.]+(dp|sp|px)|true|false|-?\d+|"[^"]*")$', x) for x in resolved.values())
    line = f'{key:14s} {name:55s} {dict(sorted(resolved.items()))}  [{len(where)} uses, e.g. {sorted(where)[0]}]'
    (plain if simple and resolved else other).append(line)
print('# plain values')
for l in plain: print(l)
print('\n# need an answer from the port')
for l in other: print(l)
