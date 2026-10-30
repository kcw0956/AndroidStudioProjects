package com.example.lyrics_mobile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TrackRules {
    private TrackRules() {}
    private static final String PRESENTATION = "(?i)(?:official(?:\\s+music)?\\s*(?:video|audio|mv)|music video|lyrics?(?: video)?|mv|pv|가사|뮤직비디오|公式(?:ミュージック)?ビデオ|4k|hd)";
    private static final Pattern BRACKETS = Pattern.compile("[\\[(【]([^\\])】]*)[\\])】]");
    private static String tidy(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }
    public static String cleanTitle(String title) {
        String input = tidy(title);
        Matcher m = BRACKETS.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(m.group(1).trim().matches(PRESENTATION) ? " " : m.group()));
        m.appendTail(out);
        return tidy(out.toString().replaceAll("(?i)\\s+(?:official(?: music)? (?:video|audio)|lyrics video)\\s*$", ""));
    }
    public static boolean alternateVersion(String text) {
        return tidy(text).toLowerCase(Locale.ROOT).matches("(?s).*(\\blive\\b|\\bcover\\b|\\bremix\\b|\\bacoustic\\b|\\btribute\\b|\\bkaraoke\\b|라이브|커버|리믹스|노래방).*" );
    }
    public static String originalQuery(String text) {
        String input = cleanTitle(text);
        Matcher m = BRACKETS.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String label = m.group(1).trim();
            boolean version = label.matches("(?i)(?:live(?:\\s+(?:at|from|in|ver(?:sion)?).*)?|(?:.*\\s+)?(?:cover|remix|acoustic|karaoke)(?:\\s+version)?|라이브.*|커버|리믹스)");
            m.appendReplacement(out, Matcher.quoteReplacement(version ? " " : m.group()));
        }
        m.appendTail(out);
        String result = out.toString().replaceAll("(?i)\\s+(?:[-|／/]\\s*)?(?:live\\s+(?:at|from|in)\\s+.+|official live(?: video)?|ライブ映像|라이브 영상)\\s*$", "");
        result = result.replaceAll("(?i)\\s+[-|]\\s*(?:live|cover|remix|acoustic|라이브|커버)\\s*$", "");
        return tidy(result);
    }
    public static String cleanArtist(String artist) {
        return tidy(tidy(artist).replaceAll("(?i)\\s*(?:[-|]\\s*(?:topic|official(?: channel)?)|\\s+official(?: youtube)? channel)\\s*$", ""));
    }
    public static String originalQuery(String title, String artist) {
        String t = originalQuery(title), a = cleanArtist(artist);
        return a.isEmpty() || normalized(t).contains(normalized(a)) ? t : tidy(t + " " + a);
    }
    public static List<String> searchQueries(String title, String artist) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String cleaned = originalQuery(title);
        String a = cleanArtist(artist);
        // Quotes delimit song names; preserve their content and artist on either side.
        String unquoted = cleaned.replaceAll("[「」『』“”\"]", " ").replaceAll("\\s+[-|/／]\\s+", " ");
        queries.add(originalQuery(tidy(unquoted), a));
        queries.add(originalQuery(cleaned, a));
        queries.remove("");
        return new ArrayList<>(queries);
    }
    static String normalized(String value) {
        return Normalizer.normalize(tidy(value), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
