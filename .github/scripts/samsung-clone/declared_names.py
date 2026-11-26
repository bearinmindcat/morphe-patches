#!/usr/bin/env python3
"""declared_names.py <apk> <sibling-stock-pkg|-> [private action prefix...]: names a renamed copy moves (ChangePackageName.kt rule)."""
import re, subprocess, sys
apk = sys.argv[1]; sib = sys.argv[2]; extra = sys.argv[3:]
out = subprocess.run(["aapt2", "dump", "xmltree", "--file", "AndroidManifest.xml", apk], capture_output=True, text=True).stdout
pkg = re.search(r'A: package="([^"]+)"', out).group(1)
names = set(); stack = []; actions = []; other = set()
VENDOR = re.compile(r'^(com\.samsung\.|com\.sec\.|samsung\.|sec\.)')
for line in out.splitlines():
    m = re.match(r'^(\s*)E: (\S+)', line)
    if m:
        d = len(m.group(1))
        while stack and stack[-1][0] >= d: stack.pop()
        stack.append((d, m.group(2))); continue
    m = re.match(r'^\s*A: http://schemas.android.com/apk/res/android:(name|authorities)\(0x[0-9a-f]+\)="([^"]*)"', line)
    if not m or not stack: continue
    tag = stack[-1][1]; parent = stack[-2][1] if len(stack) > 1 else None
    attr, val = m.groups()
    if tag in ("permission", "permission-group", "permission-tree") and attr == "name": names.add(val)
    if tag == "provider" and attr == "authorities" and parent == "application": names.update(v for v in val.split(";") if v)
    if tag == "action" and attr == "name": actions.append((val, [t for _, t in stack]))
    elif attr == "name": other.add(val)
for val, tags in actions:
    if val.startswith(pkg + ".") or any(val.startswith(p) for p in extra): names.add(val); continue
    if not VENDOR.match(val) or val in other: continue
    if sib != "-" and (val == sib or val.startswith(sib + ".")): continue
    if "activity" in tags or "activity-alias" in tags: names.add(val)
names.discard(pkg + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
print(f"// {pkg}: {len(names)}")
for n in sorted(names): print(f'    "{n}",')
