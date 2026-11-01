#!/usr/bin/env python3
"""magicres.py <app.apk> <androidhnext.apk>: the app's references to MagicOS's framework resources (package 0x02).

Off MagicOS there is no androidhnext package: a layout or style value "?androidhnext:attr/x" (a theme attribute only
MagicOS's Theme.Magic defines) fails to inflate ("Failed to resolve attribute"), and "@androidhnext:dimen/x" has no
resource behind it. Lists each such reference the app's XML files and resource table use (binary XML scanned directly,
the table through aapt2), with its name and what MagicOS's default theme (Theme.Magic, default configuration) answers,
followed down to a plain value where there is one. Prints Kotlin map entries for patches/.../honor/MagicResources.kt.
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
                # reference / attribute, or their "dynamic" forms (7 / 8) aapt2 writes for a shared library's ids
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

# the framework: names, Theme.Magic's attribute values, default-configuration values
fdump = subprocess.run(['aapt2', 'dump', 'resources', fw], capture_output=True, text=True).stdout
names, values, configs, theme, cur, in_theme = {}, {}, collections.defaultdict(set), {}, None, False
for line in fdump.splitlines():
    m = re.match(r'\s+resource (0x[0-9a-f]+) (\S+)', line)
    if m:
        cur = int(m.group(1), 16); names[cur] = m.group(2); in_theme = m.group(2) == 'style/Theme.Magic'; continue
    m = re.match(r'\s+\(([^)]*)\) (.+)$', line)
    if m and cur is not None and not in_theme:
        if m.group(1) == '' and cur not in values: values[cur] = m.group(2).strip()
        # configurations a phone can be in (not TV / watch / car): a plain value would lose them
        if m.group(1) and not re.search(r'television|watch|car', m.group(1)): configs[cur].add(m.group(1))
    m = re.match(r'\s+\w*\((0x[0-9a-f]+)\)=(.+)$', line)
    if in_theme and m: theme[int(m.group(1), 16)] = m.group(2).strip()
by_name = {v: k for k, v in names.items()}

varies = set()
def plain(v, depth=0):
    """A reference followed down to a literal (dp/sp/px, #colour, number, bool), else None; notes in `varies` the
    phone configurations (night, land, sw600dp, ...) whose value the literal would not follow."""
    if v is None or depth > 8: return None
    m = re.match(r'@(\w+/\S+)$', v)
    if m:
        r = by_name.get(m.group(1)); varies.update(configs.get(r, ()))
        return plain(values.get(r), depth + 1)
    m = re.match(r'(-?[\d.]+)(dp|sp|px|dip)$', v)
    if m: return f'{float(m.group(1))}{m.group(2)}'
    if re.match(r'#[0-9a-fA-F]{6,8}$', v): return v.lower()
    if re.match(r'-?\d+$|true$|false$', v): return v
    return None

print(f'{len(uses)} references to androidhnext in {app}')
for (kind, v), files in sorted(uses.items(), key=lambda x: x[0][1]):
    name = names.get(v, '?')
    answer = theme.get(v) if kind == '?' else values.get(v)
    varies.clear(); varies.update(configs.get(v, ()) if kind == '@' else ()); lit = plain(answer)
    print(f'  {kind}0x{v:08x} {name}: Theme.Magic -> {answer} -> {lit}{" (also " + ", ".join(sorted(varies)) + ")" if varies else ""}  ({len(files)} uses: {", ".join(sorted(files)[:3])}{", ..." if len(files) > 3 else ""})')
print('Kotlin:')
for (kind, v), files in sorted(uses.items(), key=lambda x: x[0][1]):
    varies.clear(); varies.update(configs.get(v, ()) if kind == '@' else ()); lit = plain(theme.get(v) if kind == '?' else values.get(v))
    if lit and varies: print(f'    // {kind}0x{v:08x} {names.get(v, "?")}: {lit}, but differs in {", ".join(sorted(varies))}')
    elif lit: print(f'    "{kind}0x{v:08x}" to "{lit}", // {names.get(v, "?")}')
    else: print(f'    // {kind}0x{v:08x} {names.get(v, "?")}: no plain value')
