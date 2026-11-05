#!/usr/bin/env python3
# OnePlus System Launcher stand-ins: the shared generator (../xiaomi-standins/gen.py) with ColorOS's answers and the
# launcher's Android framework additions (known.py next to this file), then the hand-written stand-ins in overrides/
# (shapes gen.py cannot write: an enum, a nested class with a base) over the generated ones. Same arguments as that
# script; see README.md.
import os, shutil, subprocess, sys

here = os.path.dirname(os.path.abspath(__file__))
rc = subprocess.call([sys.executable, os.path.join(here, '..', 'xiaomi-standins', 'gen.py'), *sys.argv[1:],
                      '--known', os.path.join(here, 'known.py')])
if rc == 0:
    out, overrides = sys.argv[2], os.path.join(here, 'overrides')
    for root, _, files in os.walk(overrides):
        for f in files:
            rel = os.path.relpath(os.path.join(root, f), overrides)
            os.makedirs(os.path.dirname(os.path.join(out, rel)), exist_ok=True)
            shutil.copyfile(os.path.join(root, f), os.path.join(out, rel))
            print('override', rel)
sys.exit(rc)
