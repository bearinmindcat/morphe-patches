#!/usr/bin/env python3
"""
inaccessible.py <app.apk> <boot dir>...

Android interfaces and superclasses the app's own classes implement or extend that a phone's framework declares
non-public (package-private). An installed app's class with such a supertype does not load at all
(IllegalAccessError: "Interface ... implemented by class ... is inaccessible"), so the patch drops the interface
(see INACCESSIBLE_INTERFACES). Boot dirs as for hidden.py; One UI's framework.jar (../../o51) hides more than AOSP:
AnimationHandler.AnimationFrameCallback is public on AOSP 13-17 but package-private on Android 11 and One UI 5.1.
"""
import collections, os, struct, sys

here = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(here, 'hidden.py')).read().split('# --- the phone')[0])
apk, boots = sys.argv[1], sys.argv[2:]
own, rel = set(), []
for d in dexes(apk):
    for t, sup, its, cdo, ci in d.classes():
        own.add(t)
        rel.append((t, sup, its))
for boot in boots:
    access = {}
    for jar in sorted(os.listdir(boot)):
        if 'org.apache.http.legacy' in jar:  # only on the class path of an app that asks for it (uses-library)
            continue
        try:
            dl = list(dexes(os.path.join(boot, jar)))
        except Exception:
            continue
        for d in dl:
            for ci in range(d.csz):
                cls, flags = struct.unpack_from('<II', d.b, d.coff + 32 * ci)
                access.setdefault(d.types[cls], flags)
    hidden = collections.defaultdict(set)
    for t, sup, its in rel:
        for s in ([sup] if sup else []) + its:
            if s in access and not access[s] & 1:  # not ACC_PUBLIC
                hidden[s].add(t)
    print(boot, {s: sorted(ts) for s, ts in sorted(hidden.items())})
