import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;

import java.io.*;
import java.util.*;

/**
 * java -cp <dexlib2 jars> Missing.java <boot dir> <app.apk> <out dir>
 *
 * What an app uses that a plain Android phone does not have. <boot dir> holds the phone's boot-classpath jars
 * (adb pull every entry of $BOOTCLASSPATH, plus /system/framework/org.apache.http.legacy.jar). Writes to <out dir>:
 *   types.txt    classes neither the app nor the phone defines (the stand-in candidates)
 *   members.txt  methods/fields of the phone's own classes that the phone lacks (vendor additions to Android
 *                classes; send them through redirectMiuiMembers), with use counts
 *   usage.json   per missing class: the methods and fields the app uses, how (static/virtual/...), who extends or
 *                implements it, whether it is caught/thrown/instantiated, and for each app class whose superclass
 *                chain leaves the app at a missing class, that class (gen.py turns manifest components into bases)
 *   abstract.json for every phone class: its abstract methods (gen.py implements them in a stand-in extending it)
 */
public class Missing {
    static final Map<String, ClassDef> BOOT = new HashMap<>();
    static final Map<String, ClassDef> APP = new HashMap<>();

    static void load(File f, Map<String, ClassDef> into) throws IOException {
        MultiDexContainer<? extends DexFile> c = DexFileFactory.loadDexContainer(f, Opcodes.getDefault());
        for (String e : c.getDexEntryNames()) for (ClassDef d : c.getEntry(e).getDexFile().getClasses()) into.putIfAbsent(d.getType(), d);
    }

    static boolean has(String cls, String name, String sig, boolean field, Set<String> seen) {
        if (cls == null || !seen.add(cls)) return false;
        ClassDef d = BOOT.get(cls);
        if (d == null) return APP.containsKey(cls);
        if (field) {
            for (Field f : d.getFields()) if (f.getName().equals(name) && f.getType().equals(sig)) return true;
        } else {
            for (Method m : d.getMethods())
                if (m.getName().equals(name) && sig.equals("(" + String.join("", m.getParameterTypes()) + ")" + m.getReturnType())) return true;
        }
        if (has(d.getSuperclass(), name, sig, field, seen)) return true;
        for (String i : d.getInterfaces()) if (has(i, name, sig, field, seen)) return true;
        return false;
    }

    static String base(String t) { return t.replaceFirst("^\\[+", ""); }
    static boolean missing(String t) { t = base(t); return t.startsWith("L") && !APP.containsKey(t) && !BOOT.containsKey(t); }

    static final class Use {
        TreeMap<String, TreeSet<String>> methods = new TreeMap<>(), fields = new TreeMap<>();
        TreeSet<String> extendedBy = new TreeSet<>(), implementedBy = new TreeSet<>();
        int typeUse; boolean newInstance, caught, thrown;
    }
    static final TreeMap<String, Use> USES = new TreeMap<>();
    static Use use(String t) { return USES.computeIfAbsent(base(t), k -> new Use()); }

    static String q(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    static String list(Collection<String> c) { StringJoiner j = new StringJoiner(", ", "[", "]"); for (String s : c) j.add(q(s)); return j.toString(); }

    public static void main(String[] a) throws Exception {
        for (File f : Objects.requireNonNull(new File(a[0]).listFiles())) if (f.getName().endsWith(".jar") || f.getName().endsWith(".apk")) load(f, BOOT);
        load(new File(a[1]), APP);
        File out = new File(a[2]); out.mkdirs();
        TreeMap<String, Integer> members = new TreeMap<>();
        for (ClassDef d : APP.values()) {
            if (d.getSuperclass() != null && missing(d.getSuperclass())) use(d.getSuperclass()).extendedBy.add(d.getType());
            for (String i : d.getInterfaces()) if (missing(i)) use(i).implementedBy.add(d.getType());
            for (Field f : d.getFields()) if (missing(f.getType())) use(f.getType()).typeUse++;
            for (Method m : d.getMethods()) {
                for (CharSequence p : m.getParameterTypes()) if (missing(p.toString())) use(p.toString()).typeUse++;
                if (missing(m.getReturnType())) use(m.getReturnType()).typeUse++;
                if (m.getImplementation() == null) continue;
                String lastNew = null;
                for (Instruction insn : m.getImplementation().getInstructions()) {
                    if (insn.getOpcode() == Opcode.THROW && lastNew != null) use(lastNew).thrown = true;
                    if (!(insn instanceof ReferenceInstruction)) continue;
                    Reference r = ((ReferenceInstruction) insn).getReference();
                    if (r instanceof TypeReference) {
                        String t = ((TypeReference) r).getType();
                        if (insn.getOpcode() == Opcode.NEW_INSTANCE) lastNew = missing(t) ? t : null;
                        if (missing(t)) { use(t).typeUse++; if (insn.getOpcode() == Opcode.NEW_INSTANCE) use(t).newInstance = true; }
                    } else if (r instanceof MethodReference) {
                        MethodReference mr = (MethodReference) r;
                        String c = base(mr.getDefiningClass());
                        String sig = "(" + String.join("", mr.getParameterTypes()) + ")" + mr.getReturnType();
                        String kind = insn.getOpcode().name.replaceFirst("^invoke-", "").replaceFirst("/range$", "");
                        if (missing(c)) use(c).methods.computeIfAbsent(mr.getName() + sig, k -> new TreeSet<>()).add(kind);
                        for (CharSequence p : mr.getParameterTypes()) if (missing(p.toString())) use(p.toString()).typeUse++;
                        if (missing(mr.getReturnType())) use(mr.getReturnType()).typeUse++;
                        if (BOOT.containsKey(c) && !APP.containsKey(c) && !has(c, mr.getName(), sig, false, new HashSet<>()))
                            members.merge(c + "->" + mr.getName() + sig, 1, Integer::sum);
                    } else if (r instanceof FieldReference) {
                        FieldReference fr = (FieldReference) r;
                        String c = base(fr.getDefiningClass());
                        String op = insn.getOpcode().name.startsWith("s") ? "s" : "i";
                        op += insn.getOpcode().name.contains("put") ? "put" : "get";
                        if (missing(c)) use(c).fields.computeIfAbsent(fr.getName() + ":" + fr.getType(), k -> new TreeSet<>()).add(op);
                        if (missing(fr.getType())) use(fr.getType()).typeUse++;
                        if (BOOT.containsKey(c) && !APP.containsKey(c) && !has(c, fr.getName(), fr.getType(), true, new HashSet<>()))
                            members.merge(c + "->" + fr.getName() + ":" + fr.getType(), 1, Integer::sum);
                    }
                }
                for (TryBlock<? extends ExceptionHandler> tb : m.getImplementation().getTryBlocks())
                    for (ExceptionHandler h : tb.getExceptionHandlers())
                        if (h.getExceptionType() != null && missing(h.getExceptionType())) use(h.getExceptionType()).caught = true;
            }
        }
        // App classes whose superclass chain leaves the app at a missing class.
        TreeMap<String, String> leaves = new TreeMap<>();
        for (ClassDef d : APP.values()) {
            String s = d.getSuperclass(); Set<String> seen = new HashSet<>();
            while (s != null && APP.containsKey(s) && seen.add(s)) s = APP.get(s).getSuperclass();
            if (s != null && missing(s)) leaves.put(d.getType(), s);
        }
        try (PrintWriter w = new PrintWriter(new File(out, "types.txt"))) { for (String t : USES.keySet()) w.println(t); }
        try (PrintWriter w = new PrintWriter(new File(out, "members.txt"))) { for (var e : members.entrySet()) w.println(e.getKey() + "\t" + e.getValue()); }
        try (PrintWriter w = new PrintWriter(new File(out, "usage.json"))) {
            w.println("{\"classes\": {");
            int n = 0;
            for (var e : USES.entrySet()) {
                Use u = e.getValue();
                StringJoiner ms = new StringJoiner(", ", "{", "}"); for (var m : u.methods.entrySet()) ms.add(q(m.getKey()) + ": " + list(m.getValue()));
                StringJoiner fs = new StringJoiner(", ", "{", "}"); for (var f : u.fields.entrySet()) fs.add(q(f.getKey()) + ": " + list(f.getValue()));
                String outer = e.getKey().contains("$") ? e.getKey().substring(0, e.getKey().lastIndexOf('$')) + ";" : null;
                w.print((n++ > 0 ? ",\n" : "") + " " + q(e.getKey()) + ": {\"methods\": " + ms + ", \"fields\": " + fs
                        + ", \"extendedBy\": " + list(u.extendedBy) + ", \"implementedBy\": " + list(u.implementedBy)
                        + ", \"typeUse\": " + u.typeUse + ", \"newInstance\": " + u.newInstance + ", \"caught\": " + u.caught
                        + ", \"thrown\": " + u.thrown + ", \"outerOnPhone\": " + (outer != null && BOOT.containsKey(outer)) + "}");
            }
            w.println("},");
            StringJoiner lv = new StringJoiner(", ", "{", "}"); for (var e : leaves.entrySet()) lv.add(q(e.getKey()) + ": " + q(e.getValue()));
            w.println("\"leaves\": " + lv + "}");
        }
        try (PrintWriter w = new PrintWriter(new File(out, "abstract.json"))) {
            StringJoiner all = new StringJoiner(",\n ", "{", "}");
            for (ClassDef d : BOOT.values()) {
                if ((d.getAccessFlags() & 0x0400) == 0 || (d.getAccessFlags() & 0x0200) != 0) continue; // abstract classes only
                List<String> abs = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (ClassDef c = d; c != null; c = c.getSuperclass() == null ? null : BOOT.get(c.getSuperclass())) {
                    for (Method m : c.getMethods()) {
                        String k = m.getName() + "(" + String.join("", m.getParameterTypes()) + ")" + m.getReturnType();
                        if (!seen.add(k)) continue;
                        if ((m.getAccessFlags() & 0x0400) != 0) abs.add(k);
                    }
                }
                if (!abs.isEmpty()) all.add(q(d.getType()) + ": " + list(abs));
            }
            w.println(all);
        }
        int refs = 0; for (Use u : USES.values()) refs += u.typeUse + u.methods.size() + u.fields.size();
        System.out.println("app classes " + APP.size() + ": " + USES.size() + " missing classes, " + members.size() + " missing members on the phone's classes");
        TreeMap<String, Integer> pk = new TreeMap<>();
        for (String t : USES.keySet()) { String[] p = t.substring(1).split("/"); pk.merge(p.length > 2 ? p[0] + "/" + p[1] : p[0], 1, Integer::sum); }
        for (var e : pk.entrySet()) System.out.println("  " + e.getKey() + ": " + e.getValue());
    }
}
