package com.example.lyrics_mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class DirectAiLyricsTest {
    private final SongInfo song = new SongInfo("Test", "Artist");
    @Test public void candidateIsAlwaysUnverifiedAndHasNoInventedTiming() {
        DataResult<LrcLibClient.Lyrics> r = DirectAiLyrics.parse("{\"status\":\"ok\",\"title\":\"Test\",\"artist\":\"Artist\",\"lyrics\":\"Synthetic line\",\"synced\":\"invented\"}", song, "openai");
        assertTrue(r.isSuccess()); assertTrue(r.value.aiSupplied); assertEquals("", r.value.synced);
        assertTrue(r.value.source.contains("미검증")); assertTrue(r.value.source.contains("OpenAI"));
    }
    @Test public void refusalAndUnavailableAreFailuresNotLyrics() {
        AiFailure refusal = DirectAiLyrics.failure(new AiFailure("REFUSAL", "refused", "원본 가사는 계속 볼 수 있어요", AiFailure.Action.CLOSE));
        assertFalse(refusal.message.contains("원본 가사는 계속"));
        assertEquals("AI_LYRICS_RESTRICTED", DirectAiLyrics.parse("{\"status\":\"restricted\"}", song, "gemini").failure.code);
        assertEquals("AI_LYRICS_UNAVAILABLE", DirectAiLyrics.parse("{\"status\":\"unavailable\"}", song, "gemini").failure.code);
    }
    @Test public void invalidOrWrongSongNeverSavedAsLyrics() {
        for (String s : new String[]{"I cannot provide that", "{}", "[]", "null", "{\"status\":\"ok\",\"title\":\"Other\",\"artist\":\"Artist\",\"lyrics\":\"wrong\"}", "{\"status\":\"ok\",\"title\":\"Test\",\"artist\":\"Artist\",\"lyrics\":false}"})
            assertEquals("AI_LYRICS_INVALID", DirectAiLyrics.parse(s, song, "openai").failure.code);
    }
}
