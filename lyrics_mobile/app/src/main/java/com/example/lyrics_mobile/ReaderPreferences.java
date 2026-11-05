package com.example.lyrics_mobile;

import android.content.Context;
import android.content.SharedPreferences;

/** Reading preferences are independent of API credentials and generation settings. */
public final class ReaderPreferences {
    private final SharedPreferences prefs;
    public ReaderPreferences(Context context) {
        prefs = context.getSharedPreferences("reader_preferences", Context.MODE_PRIVATE);
    }
    public boolean combined() { return prefs.getBoolean("combined", true); }
    public String theme() { return AppPalette.normalizeId(prefs.getString("theme", "charcoal")); }
    public void theme(String id) { prefs.edit().putString("theme", AppPalette.normalizeId(id)).apply(); }
    public boolean original() { return prefs.getBoolean("original", true); }
    public boolean translation() { return prefs.getBoolean("translation", true); }
    public boolean pronunciation() { return prefs.getBoolean("pronunciation", false); }
    public boolean serif() { return prefs.getBoolean("serif", false); }
    public boolean motion() { return prefs.getBoolean("motion", true); }
    public boolean preGenerate() { return prefs.getBoolean("pre_generate", false); }
    public void preGenerate(boolean value) { prefs.edit().putBoolean("pre_generate", value).apply(); }
    public void motion(boolean value) { prefs.edit().putBoolean("motion", value).apply(); }
    public int size() { return Math.max(14, Math.min(28, prefs.getInt("size", 18))); }
    public int spacing() { return Math.max(4, Math.min(20, prefs.getInt("spacing", 12))); }
    public void combined(boolean value) { prefs.edit().putBoolean("combined", value).apply(); }
    public void save(boolean original, boolean translation, boolean pronunciation, boolean serif, int size, int spacing) {
        prefs.edit().putBoolean("original", original).putBoolean("translation", translation)
                .putBoolean("pronunciation", pronunciation).putBoolean("serif", serif)
                .putInt("size", size).putInt("spacing", spacing).apply();
    }
}
