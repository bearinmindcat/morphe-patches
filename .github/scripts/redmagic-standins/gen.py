#!/usr/bin/env python3
# RedMagic (Nubia / ZTE) stand-ins: the shared generator (../xiaomi-standins/gen.py) with RedMagic's answers (known.py
# next to this file) and runtime classes (runtime/). Same arguments as that script; see README.md.
import os, subprocess, sys

here = os.path.dirname(os.path.abspath(__file__))
sys.exit(subprocess.call([sys.executable, os.path.join(here, '..', 'xiaomi-standins', 'gen.py'), *sys.argv[1:],
                          '--known', os.path.join(here, 'known.py')]))
