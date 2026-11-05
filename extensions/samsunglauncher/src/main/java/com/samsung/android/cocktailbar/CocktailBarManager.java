package com.samsung.android.cocktailbar;

/** Generated stand-in (.github/scripts/samsung-standins): names and signatures One UI Home uses, answering "absent" off One UI (no Samsung code). */
public class CocktailBarManager {
    public CocktailBarManager() {
    }
    public int[] getAllCocktailIds() {
        return new int[0];
    }
    public com.samsung.android.cocktailbar.Cocktail getCocktail(int p0) {
        return null;
    }
    public int[] getEnabledCocktailIds() {
        return new int[0];
    }
    public static com.samsung.android.cocktailbar.CocktailBarManager getInstance(android.content.Context p0) {
        return new com.samsung.android.cocktailbar.CocktailBarManager();
    }
    public void notifyCocktailVisibiltyChanged(int p0, int p1) {
    }
    public void registerSystemUiVisibilityListener(com.samsung.android.cocktailbar.CocktailBarManager.SystemUiVisibilityListener p0) {
    }
    public boolean requestToUpdateCocktail(int p0) {
        return false;
    }
    public void setEnabledCocktailIds(int[] p0) {
    }
    public void unregisterSystemUiVisibilityListener(com.samsung.android.cocktailbar.CocktailBarManager.SystemUiVisibilityListener p0) {
    }
    public void updateCocktailBarWindowType(int p0) {
    }
    public static class SystemUiVisibilityListener {
        public SystemUiVisibilityListener() {
        }
    }
}
