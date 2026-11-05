package com.example.lyrics_mobile;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class LyricsRecoveryTest {
    private SongInfo song(String title, String artist, String id, long duration) {
        return new SongInfo(title, artist, "Album", id, "", duration);
    }
    private SongInfo raw() { return song("Title", "Artist", "raw", 180000); }
    private DataResult<LrcLibClient.Lyrics> missing() { return DataResult.fail(DataResult.missing("missing")); }
    private LyricsAiClient.AiTextResult metadata(String text) { return new LyricsAiClient.AiTextResult(true, text, ""); }
    @Test public void defaultNeverCallsAiOrRepeatsInitialLookup() {
        LyricsRecovery recovery = new LyricsRecovery((t,a) -> { fail("catalog"); return null; },
                s -> { fail("fetch"); return null; }, s -> { fail("AI"); return null; }, null);
        DataResult<?> result = recovery.recover(raw(), new OperationScope(), false);
        assertEquals("NOT_FOUND", result.failure.code);
        assertTrue(result.failure.message.contains("조회하지 않았어요"));
        assertFalse(LyricsRecovery.UNAVAILABLE.isAvailable());
        assertEquals("PROVIDER_UNAVAILABLE", LyricsRecovery.UNAVAILABLE.find(raw(), new OperationScope()).failure.code);
    }
    @Test public void consentOnlyUsesVerifiedCatalogLyricsAndProvenance() {
        int[] calls = new int[3];
        LyricsRecovery recovery = new LyricsRecovery((t,a) -> {
            calls[0]++; assertEquals("Native title", t);
            return DataResult.ok(Collections.singletonList(song(t,a,"verified",181000)));
        }, s -> {
            calls[1]++; assertEquals("verified", s.getCatalogId());
            LrcLibClient.Lyrics lyrics = new LrcLibClient.Lyrics(); lyrics.plain = "Source text";
            return DataResult.ok(lyrics);
        }, s -> { calls[2]++; return metadata("{\"title\":\"Native title\",\"artist\":\"Artist\",\"lyrics\":\"invented\",\"url\":\"https://bad.test\"}"); }, null);
        DataResult<LrcLibClient.Lyrics> result = recovery.recover(raw(), new OperationScope(), true);
        assertTrue(result.isSuccess()); assertEquals("Source text", result.value.plain);
        assertEquals("LRCLIB", result.value.source);
        assertTrue(result.value.notice.contains("LRCLIB")); assertArrayEquals(new int[]{1,1,1}, calls);
    }
    @Test public void conservativeIdentityRequiresArtistTitleDurationAndCatalog() {
        assertTrue(LyricsRecovery.identityMatches(raw(), "Title", song("Title","Artist","1",182000)));
        for (SongInfo candidate : Arrays.asList(song("Title","Other","1",180000),
                song("Other","Artist","1",180000), song("Title","Artist","",180000),
                song("Title","Artist","1",182001), song("Title","Artist","1",0),
                song("Title (Live)","Artist","1",180000)))
            assertFalse(LyricsRecovery.identityMatches(raw(), "Title", candidate));
        assertFalse(LyricsRecovery.identityMatches(song("Title","Artist","x",0), "Title", raw()));
        assertFalse(LyricsRecovery.identityMatches(song("Title (Live)","Artist","x",180000), "Title", raw()));
    }
    @Test public void ambiguousEditionsNeverFetched() {
        LyricsRecovery recovery = new LyricsRecovery((t,a) -> DataResult.ok(Arrays.asList(
                song(t,a,"1",180000), song(t,a,"2",180000))), s -> { fail("ambiguous fetch"); return null; },
                s -> metadata("{\"title\":\"Native\",\"artist\":\"Artist\"}"), null);
        assertEquals("NOT_FOUND", recovery.recover(raw(),new OperationScope(),true).failure.code);
    }
    @Test public void changedArtistUrlsAndInvalidJsonNeverReachCatalog() {
        for (String text : Arrays.asList("{\"title\":\"Title\",\"artist\":\"Other\"}",
                "{\"title\":\"https://example.com\",\"artist\":\"Artist\"}", "invalid",
                "{\"title\":12,\"artist\":\"Artist\"}")) {
            LyricsRecovery recovery = new LyricsRecovery((t,a) -> { fail("catalog"); return null; },
                    s -> { fail("fetch"); return null; }, s -> metadata(text), null);
            assertEquals("NOT_FOUND", recovery.recover(raw(),new OperationScope(),true).failure.code);
        }
    }
    @Test public void cleanupRetainsVersionAndStopsOnServiceFailures() {
        for (String code : Arrays.asList("SERVICE_429", "SERVICE_503", "NETWORK")) {
            LyricsRecovery recovery = new LyricsRecovery((t,a) -> {
                assertEquals("Title", t);
                return DataResult.fail(new AiFailure(code,"error","error",AiFailure.Action.RETRY));
            }, s -> { fail("fetch"); return null; }, s -> { fail("AI"); return null; }, null);
            assertEquals(code, recovery.recover(song("Title (Official Video)","Artist","x",180000),
                    new OperationScope(),true).failure.code);
        }
    }
    @Test public void secondaryRunsBeforeAiAndFailureStopsPipeline() {
        LyricsRecovery.OfficialProvider provider = new LyricsRecovery.OfficialProvider() {
            public boolean isAvailable() { return true; }
            public String sourceName() { return "Test source"; }
            public DataResult<LrcLibClient.Lyrics> find(SongInfo song, OperationScope scope) {
                return DataResult.fail(DataResult.service(503,"Test source"));
            }
        };
        LyricsRecovery recovery = new LyricsRecovery((t,a) -> DataResult.ok(Collections.emptyList()),
                s -> missing(), s -> { fail("AI"); return null; }, provider);
        assertEquals("SERVICE_503", recovery.recover(raw(),new OperationScope(),true).failure.code);
    }
    @Test public void cancellationPreventsAllWork() {
        OperationScope scope = new OperationScope(); scope.cancel();
        LyricsRecovery recovery = new LyricsRecovery((t,a) -> { fail(); return null; },
                s -> { fail(); return null; }, s -> { fail(); return null; }, null);
        assertFalse(recovery.recover(raw(),scope,true).isSuccess());
    }
    @Test public void missingDurationPreventsPaidMetadataAttempt() {
        LyricsRecovery recovery = new LyricsRecovery((t,a) -> { fail("catalog"); return null; },
                s -> { fail("fetch"); return null; }, s -> { fail("AI"); return null; }, null);
        assertEquals("NOT_FOUND", recovery.recover(song("Title", "Artist", "x", 0), new OperationScope(), true).failure.code);
    }
    @Test public void catalogStorefrontFollowsTitleScript() {
        assertEquals("JP", LyricsRecovery.catalogCountry("夜に駆ける"));
        assertEquals("JP", LyricsRecovery.catalogCountry("夜"));
        assertEquals("KR", LyricsRecovery.catalogCountry("봄날"));
        assertEquals("US", LyricsRecovery.catalogCountry("Spring Day"));
    }
}
