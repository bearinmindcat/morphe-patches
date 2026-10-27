# Samsung stand-in generator
# Off One UI, Samsung apps call framework classes that only One UI has (com.samsung.android.feature.SemFloatingFeature
# and the like) and die with NoClassDefFoundError. These two scripts write Java stand-ins for exactly the classes,
# methods and fields an app references, answering "absent" (false / 0 / "" / null, a getter's own default argument,
# a fresh instance for getInstance()). They carry Samsung's names and signatures only, no Samsung code; on a Samsung
# phone the framework's own classes load first, so the stand-ins never run there.
#
#   for d in classes*.dex; do baksmali list types $d; done | sort -u > types.txt
#   for d in classes*.dex; do baksmali list classes $d; done | sort -u > defined.txt
#   comm -23 types.txt defined.txt | grep -E '^Lcom/samsung/|^Lcom/sec/|^Landroid/.*/Sem[A-Z]' > samsung-ext.txt
#   for d in classes*.dex; do baksmali d -j 4 $d -o smali; done
#   python3 analyze.py            # -> usage.json
#   python3 gen.py <extension java src dir>
