# Shared answers for RedMagic OS (Nubia / ZTE): the framework classes its apps call (com.zte.*, cn.nubia.*, nubia.os.*,
# Nubia's additions inside android.*), read by ../xiaomi-standins/gen.py through gen.py next to this file. Keys are
# dex-style like the Xiaomi file: "Lowner;->name:type" (field initializer, a Java expression) or
# "Lowner;->name(params)return" (method body, Java statements; parameters are p0, p1, ...; RUNTIME is the app's runtime
# package, see runtime/). Off RedMagic OS the apps should behave as on a phone without ZTE's framework: no ZTE
# features, no game-assist or performance services, no game keys (shoulder triggers), no fan.
import os

HEADER = ('Generated stand-in (.github/scripts/redmagic-standins): names and signatures {app} uses, answering "absent" '
          'off RedMagic OS (no Nubia or ZTE code).')

# ZTE's framework: its own packages and Nubia's, and Nubia's additions inside android.* (android.util.NubiaConfig,
# android.view.NubiaInput). Only classes the phone lacks are written; the app's own com.zte / cn.nubia classes stay.
ONLY = r'^L(com/zte/|cn/nubia/|nubia/|zte/|android/.*/Nubia|android/.*\$Nubia)'

RUNTIME_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'runtime')

SUPERS = {}

FIELDS = {}

METHODS = {
    # ZTE's feature switches (com.zte.feature.Feature, like SystemProperties, which the apps also look up by reflection):
    # the port's answer for a feature where it has one (runtime Fallbacks.feature, e.g. which Game Space parts exist
    # off RedMagic OS), else the caller's default -- no ZTE feature is on.
    'Lcom/zte/feature/Feature;->get(Ljava/lang/String;)Ljava/lang/String;': 'return RUNTIME.RedMagic.feature(p0, "");',
    'Lcom/zte/feature/Feature;->get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;': 'return RUNTIME.RedMagic.feature(p0, p1);',
    'Lcom/zte/feature/Feature;->getBoolean(Ljava/lang/String;Z)Z': 'return RUNTIME.RedMagic.featureBoolean(p0, p1);',
    'Lcom/zte/feature/Feature;->getInt(Ljava/lang/String;I)I': 'return RUNTIME.RedMagic.featureInt(p0, p1);',
    'Lcom/zte/feature/Feature;->getLong(Ljava/lang/String;J)J': 'return RUNTIME.RedMagic.featureLong(p0, p1);',
    # Nubia's build configuration values: none.
    'Landroid/util/NubiaConfig;->getValue(Ljava/lang/String;)Ljava/lang/String;': 'return null;',
}
