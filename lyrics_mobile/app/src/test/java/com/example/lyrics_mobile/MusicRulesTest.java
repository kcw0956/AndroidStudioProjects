package com.example.lyrics_mobile;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class MusicRulesTest {
    @Test public void removesPresentationLabelsWithoutChangingSong() {
        assertEquals("Song", TrackRules.cleanTitle("Song (Official Video)"));
        assertEquals("Song", TrackRules.cleanTitle("Song [Lyrics]"));
    }
    @Test public void identifiesAlternateVersionsWithoutMatchingSubstrings() {
        assertTrue(TrackRules.alternateVersion("Song (Live at Wembley)"));
        assertTrue(TrackRules.alternateVersion("Song 커버"));
        assertFalse(TrackRules.alternateVersion("Lives and dreams"));
        assertFalse(TrackRules.alternateVersion("Discovery"));
    }
    @Test public void originalQueryRemovesLiveVenueSuffix() {
        assertEquals("Song", TrackRules.originalQuery("Song (Live at Wembley)"));
    }
    @Test public void credentialsRequireACRDomainAndAllFields() {
        assertEquals("", RecognitionConfig.validate("identify-eu-west-1.acrcloud.com", "key", "secret"));
        assertFalse(RecognitionConfig.validate("https://example.com", "key", "secret").isEmpty());
        assertFalse(RecognitionConfig.validate("evilacrcloud.com", "key", "secret").isEmpty());
        assertFalse(RecognitionConfig.validate("identify.acrcloud.com", "", "secret").isEmpty());
    }
    @Test public void wavHasRealPcmHeaderAndCorrectLength() {
        byte[] wav = AudioSampleRecorder.wav(new byte[320]);
        assertEquals(364, wav.length);
        ByteBuffer view = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(356, view.getInt(4));
        assertEquals(16000, view.getInt(24));
        assertEquals(320, view.getInt(40));
        assertEquals('R', wav[0]);
        assertEquals('W', wav[8]);
    }
    @Test public void cacheVariantsIncludeOriginalModelLanguageAndPrompt() {
        SongInfo song = new SongInfo("Song", "Artist");
        String a = MusicStore.variant(song, "one", "ko", "model-a", "translation");
        assertNotEquals(a, MusicStore.variant(song, "two", "ko", "model-a", "translation"));
        assertNotEquals(a, MusicStore.variant(song, "one", "en", "model-a", "translation"));
        assertNotEquals(a, MusicStore.variant(song, "one", "ko", "model-b", "translation"));
        assertNotEquals(a, MusicStore.variant(song, "one", "ko", "model-a", "pronunciation"));
    }
    @Test public void recordingIdentitySeparatesVersions() {
        assertNotEquals(new SongInfo("Song", "Artist").identity(), new SongInfo("Song Live", "Artist").identity());
    }
}
