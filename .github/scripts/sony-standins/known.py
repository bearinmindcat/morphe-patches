# Shared answers for Sony's Xperia framework (the com.sonyericsson.* / com.sonymobile.* / com.sony.* system libraries an
# Xperia app links to), read by ../xiaomi-standins/gen.py through gen.py next to this file. Keys are dex-style like the
# Xiaomi file: "Lowner;->name:type" (field initializer, a Java expression) or "Lowner;->name(params)return" (method
# body, Java statements; parameters are p0, p1, ...). Off Xperia the apps should behave as on an Xperia without that
# library: Sony's usage reports (Idd) go nowhere, no Sony feature is present.

HEADER = ('Generated stand-in (.github/scripts/sony-standins): names and signatures {app} uses, answering "absent" '
          'off Xperia (no Sony code).')

# Sony's framework: its system libraries under the Sony Ericsson, Sony Mobile and Sony names. Only classes the phone
# lacks AND the app does not carry itself are written (Xperia Home bundles com.sonyericsson.idd.probe.*, the event
# definitions, but not com.sonyericsson.idd.api.Idd, the system library that sends them).
ONLY = r'^L(com/sonyericsson/|com/sonymobile/|com/sony/)'

SUPERS = {}

FIELDS = {}

# Idd ("com.sonyericsson.idd", a required library of Xperia Home) is Sony's usage-data client: on Xperia it hands
# each event to Sony's data collection service. The stand-in keeps the events on the phone, i.e. drops them
# (gen.py's default body for a void method); the patch also tells the app Idd is unsupported, so it builds none.
METHODS = {}

# Fields Sony's libraries only read by reflection (none found yet), as in ../xiaomi-standins/known.py.
REFLECTED = {}
