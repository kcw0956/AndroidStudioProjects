package com.example.lyrics_mobile;

/** Semantic colors shared by native controls and the lyric reader. */
public final class AppPalette {
    public final int background, surface, panel, text, muted, accent, onAccent, line, error;
    private AppPalette(int background, int surface, int panel, int text, int muted, int accent, int onAccent, int line, int error) {
        this.background = background; this.surface = surface; this.panel = panel; this.text = text;
        this.muted = muted; this.accent = accent; this.onAccent = onAccent; this.line = line; this.error = error;
    }
    public static AppPalette charcoal() {
        return new AppPalette(0xff10110f, 0xff10110f, 0xff1c1e19, 0xffeeeee7, 0xffb0b5a7, 0xffdfff62, 0xff10110f, 0xff34382e, 0xffffa080);
    }
    public static String normalizeId(String id) { return "orange_green".equals(id) ? "orange_green" : "charcoal"; }
    public static AppPalette forId(String id) {
        if ("orange_green".equals(normalizeId(id)))
            return new AppPalette(0xffff791b, 0xff153e29, 0xff205337, 0xfffff8df, 0xffd1dfbf, 0xffd9ff74, 0xff12301f, 0xff567b56, 0xffffbc96);
        return charcoal();
    }
}
