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
    public boolean original() { return prefs.getBoolean("original", true); }
    public boolean translation() { return prefs.getBoolean("translation", true); }
    public boolean pronunciation() { return prefs.getBoolean("pronunciation", false); }
    public boolean serif() { return prefs.getBoolean("serif", false); }
    public int size() { return Math.max(14, Math.min(28, prefs.getInt("size", 18))); }
    public int spacing() { return Math.max(4, Math.min(20, prefs.getInt("spacing", 12))); }
    public void combined(boolean value) { prefs.edit().putBoolean("combined", value).apply(); }
    public void save(boolean original, boolean translation, boolean pronunciation, boolean serif, int size, int spacing) {
        prefs.edit().putBoolean("original", original).putBoolean("translation", translation)
                .putBoolean("pronunciation", pronunciation).putBoolean("serif", serif)
                .putInt("size", size).putInt("spacing", spacing).apply();
    }
}
