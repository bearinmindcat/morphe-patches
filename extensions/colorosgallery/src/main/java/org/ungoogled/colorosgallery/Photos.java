package org.ungoogled.colorosgallery;

/** Answers for OnePlus Photos' ColorOS-only checks off ColorOS. */
public final class Photos {
    private Photos() {
    }

    /**
     * Photos' "show the welcome statement?" version check (ColorOS 14 and later show it; older ColorOS covered it in
     * the phone's own setup). Off ColorOS nothing has been agreed, so the statement is shown.
     */
    public static boolean showStatement(int osVersion) {
        return true;
    }

    /**
     * Photos assumes All files access outside China's builds (a system app there has it). Off ColorOS it has to be
     * granted, so Photos checks it as its China build does and asks for it.
     */
    public static boolean checkAllFilesAccess() {
        return true;
    }

    /**
     * ColorOS's share-sheet item (com.oplus.widget.OplusItem) for an app that can receive the share: its icon and
     * label from Android's own ResolveInfo, as ColorOS's OplusGalleryLoadIconHelper would give them. Null if it fails.
     */
    public static Object resolverItem(android.content.Intent intent, android.content.pm.ResolveInfo info,
                                      android.content.pm.PackageManager pm) {
        if (info == null || pm == null) return null;
        try {
            Class<?> type = Class.forName("com.oplus.widget.OplusItem");
            Object item = type.getDeclaredConstructor().newInstance();
            String label = String.valueOf(info.loadLabel(pm));
            type.getMethod("setIcon", android.graphics.drawable.Drawable.class).invoke(item, info.loadIcon(pm));
            type.getMethod("setText", String.class).invoke(item, label);
            type.getMethod("setLabel", String.class).invoke(item, label);
            return item;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
