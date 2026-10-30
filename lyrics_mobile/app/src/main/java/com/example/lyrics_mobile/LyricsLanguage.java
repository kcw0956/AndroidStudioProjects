package com.example.lyrics_mobile;

/** Script detection is not language identification, especially for romanized lyrics. */
public final class LyricsLanguage {
    private LyricsLanguage() {}
    public static boolean hasJapanese(String text) { return text != null && text.matches("(?s).*[\\u3041-\\u3096\\u30a1-\\u30fa\\uff66-\\uff9d].*"); }
    public static boolean hasKorean(String text) { return text != null && text.matches("(?s).*[\\u1100-\\u11ff\\u3131-\\u318e\\uac00-\\ud7a3].*"); }
    public static boolean hasHan(String text) { return text != null && text.matches("(?s).*[\\u3400-\\u9fff].*"); }
    public static boolean isLatinOnly(String text) {
        return text != null && text.matches("(?s).*[A-Za-z\\u00c0-\\u024f].*") && !hasJapanese(text) && !hasKorean(text) && !hasHan(text)
                && !text.matches("(?s).*[\\u0400-\\u052f\\u0600-\\u06ff].*");
    }
    public static String describe(String text) {
        if (hasJapanese(text) && hasKorean(text)) return "일본어·한국어 문자 혼합";
        if (hasJapanese(text)) return "일본어 문자";
        if (hasKorean(text)) return "한국어 문자";
        if (hasHan(text)) return "한자 · 중국어/일본어 등 언어 미확인";
        if (isLatinOnly(text)) return "라틴 문자 · 언어 미확인/로마자 가능";
        return "언어 미확인";
    }
}
