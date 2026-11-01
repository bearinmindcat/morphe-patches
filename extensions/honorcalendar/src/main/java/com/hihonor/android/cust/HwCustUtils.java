package com.hihonor.android.cust;

/** Generated stand-in (.github/scripts/honor-standins): names and signatures Honor Calendar uses, answering "absent" off MagicOS (no Honor code). */
public class HwCustUtils {
    public static java.lang.Object createObj(java.lang.Class p0, java.lang.Object[] p1) {
        
        Object[] args = p1 == null ? new Object[0] : p1;
        Class<?> type = p0;
        try { type = Class.forName(p0.getName() + "Impl", true, p0.getClassLoader()); } catch (Throwable e) { }
        for (Class<?> c : type == p0 ? new Class<?>[]{p0} : new Class<?>[]{type, p0}) {
            for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                Class<?>[] want = k.getParameterTypes();
                if (want.length != args.length) continue;
                boolean fits = true;
                for (int i = 0; i < want.length && fits; i++) fits = args[i] == null ? !want[i].isPrimitive() : (want[i].isPrimitive() || want[i].isInstance(args[i]));
                if (!fits) continue;
                try { k.setAccessible(true); return k.newInstance(args); } catch (Throwable e) { }
            }
        }
        return null;
    }
}
