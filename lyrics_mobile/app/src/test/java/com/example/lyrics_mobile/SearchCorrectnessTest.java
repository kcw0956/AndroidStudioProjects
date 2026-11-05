package com.example.lyrics_mobile;

import com.google.gson.JsonObject;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class SearchCorrectnessTest {
    @Test public void quotedYoutubeTitleKeepsArtistAndSong() {
        assertEquals("Vaundy しわあわせ", TrackRules.searchQueries("Vaundy「しわあわせ」(Live at Budokan) [Official Video]", "Vaundy - Topic").get(0));
    }
    @Test public void originalQueryDoesNotLoseArtist() {
        assertEquals("しわあわせ Vaundy", TrackRules.originalQuery("しわあわせ (Live at Budokan)", "Vaundy - Topic"));
    }
    @Test public void preservesLegitimateTitles() {
        for (String title : Arrays.asList("Live and Let Die", "I Want to Live", "Live", "Cover Me", "Song (I Live Here)", "Song (Part II)")) assertEquals(title, TrackRules.originalQuery(title));
    }
    @Test public void presentationCleanupIsConservative() {
        assertEquals("Song", TrackRules.cleanTitle("Song (Official Video)"));
        assertEquals("Song", TrackRules.originalQuery("Song - Live"));
        assertEquals("Song Artist", TrackRules.searchQueries("Song / Artist [Lyrics]", "").get(0));
        assertTrue(TrackRules.searchQueries(null, null).isEmpty());
    }
    @Test public void versionDetectionDoesNotMistakeOrdinaryLiveTitles() {
        assertFalse(TrackRules.alternateVersion("I Want to Live"));
        assertFalse(TrackRules.alternateVersion("Song (I Live Here)"));
        assertTrue(TrackRules.alternateVersion("Song (Live at Budokan)"));
        assertEquals("Vaundy しわあわせ", TrackRules.searchQueries("Vaundy「しわあわせ」 / THE FIRST TAKE", "THE FIRST TAKE").get(0));
    }
    @Test public void rankArtistBeforeWrongArtistExactTitleAndDeduplicate() {
        SongInfo correct = song("灯火", "Vaundy", "one", 179000);
        SongInfo duplicate = song("灯火", "Vaundy", "two", 179500);
        SongInfo cover = song("Tomoshibi", "Another artist", "three", 179000);
        SongInfo live = song("灯火 (Live)", "Vaundy", "four", 240000);
        List<SongInfo> results = SongSearchClient.mergeAndRank(Arrays.asList(cover, correct, duplicate, live), "Tomoshibi", "Vaundy");
        assertEquals(3, results.size());
        assertSame(correct, results.get(0));
        assertTrue(results.contains(live));
    }
    @Test public void sameCatalogIdAcrossLanguagesIsOneSong() {
        assertEquals(1, SongSearchClient.mergeAndRank(Arrays.asList(song("灯火", "Vaundy", "1", 179000), song("Tomoshibi", "Vaundy", "1", 179000)), "Tomoshibi", "").size());
    }
    @Test public void languageLabelsDoNotClaimLatinIsEnglishOrHanIsJapanese() {
        assertEquals("일본어 문자", LyricsLanguage.describe("君のうた"));
        assertEquals("한국어 문자", LyricsLanguage.describe("너의 노래"));
        assertEquals("라틴 문자 · 언어 미확인/로마자 가능", LyricsLanguage.describe("kimi no uta"));
        assertEquals("라틴 문자 · 언어 미확인/로마자 가능", LyricsLanguage.describe("Hello world"));
        assertTrue(LyricsLanguage.describe("灯火").contains("언어 미확인"));
        assertEquals("언어 미확인", LyricsLanguage.describe(""));
    }
    @Test public void lyricsAlternativeMustMatchIdentityAndDuration() {
        SongInfo song = song("灯火", "Vaundy", "1", 179000);
        JsonObject record = record("灯火", "Vaundy", 179);
        assertTrue(LrcLibClient.matches(record, song, true));
        assertFalse(LrcLibClient.matches(record("灯火", "Cover artist", 179), song, true));
        assertFalse(LrcLibClient.matches(record("灯火", "Vaundy", 183), song, true));
        assertFalse(LrcLibClient.matches(record("別の歌", "Vaundy", 179), song, true));
        assertFalse(LrcLibClient.matches(record, new SongInfo("灯火", "Vaundy"), true));
    }
    @Test public void originalScriptPreferenceNeedsMetadataEvidence() {
        LrcLibClient.Lyrics romanized = new LrcLibClient.Lyrics(); romanized.plain = "kimi no uta";
        LrcLibClient.Lyrics japanese = new LrcLibClient.Lyrics(); japanese.plain = "君のうた";
        assertTrue(LrcLibClient.preferOriginalScript(japanese, romanized, song("灯火", "Vaundy", "1", 179000)));
        assertFalse(LrcLibClient.preferOriginalScript(japanese, romanized, song("Hello", "Someone", "2", 179000)));
    }
    @Test public void syncedOriginalCanReplaceRomanizedPlain() {
        JsonObject record = record("歌", "Artist", 180);
        record.addProperty("plainLyrics", "kimi no uta");
        record.addProperty("syncedLyrics", "[00:01.00]君のうた");
        assertEquals("君のうた", LrcLibClient.parse(record).plain);
        assertEquals("歌", LrcLibClient.stripTimestamps("[ar:Artist]\n[00:01.00][00:02.00]歌"));
    }
    private static SongInfo song(String title, String artist, String id, long duration) { return new SongInfo(title, artist, "", id, "", duration); }
    private static JsonObject record(String title, String artist, double duration) {
        JsonObject record = new JsonObject(); record.addProperty("trackName", title); record.addProperty("artistName", artist); record.addProperty("duration", duration); return record;
    }

    @Test public void publicAliasMustMatchExactlyBeforeNativeExpansion() {
        String json = "{\"works\":[{\"title\":\"灯火\",\"aliases\":[{\"name\":\"TOMOSHIBI\"}]},{\"title\":\"永遠の灯\",\"aliases\":[{\"name\":\"eien no tomoshibi\"}]}]}";
        assertEquals(java.util.Arrays.asList("灯火"), NativeTitleAliases.parse(json, "tomoshibi"));
        assertEquals("tomoshibi", NativeTitleAliases.token("Vaundy tomoshibi"));
        assertTrue(NativeTitleAliases.token("灯火").isEmpty());
    }
}
