#!/usr/bin/env python3
# Sony (Xperia) stand-ins: the shared generator (../xiaomi-standins/gen.py) with Sony's answers (known.py next to this
# file). Same arguments as that script; see README.md.
import os, subprocess, sys

here = os.path.dirname(os.path.abspath(__file__))
sys.exit(subprocess.call([sys.executable, os.path.join(here, '..', 'xiaomi-standins', 'gen.py'), *sys.argv[1:],
                          '--known', os.path.join(here, 'known.py')]))
