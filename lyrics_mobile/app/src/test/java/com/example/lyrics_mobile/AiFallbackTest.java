package com.example.lyrics_mobile;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class AiFallbackTest {
    private LyricsAiClient.AiTextResult ok() { return new LyricsAiClient.AiTextResult(true, "result", ""); }
    private LyricsAiClient.AiTextResult error(String provider) {
        return new LyricsAiClient.AiTextResult(new AiFailure("BILLING", "한도 부족", "사용 한도를 확인해 주세요", AiFailure.Action.BILLING).forProvider(provider));
    }
    @Test public void gptSuccessNeverCallsGemini() {
        List<String> calls = new ArrayList<>();
        LyricsAiClient.AiTextResult result = AiFallback.run("openai", true, new OperationScope(), p -> { calls.add(p); return ok(); });
        assertEquals(Arrays.asList("openai"), calls); assertEquals("openai", result.usedProvider);
    }
    @Test public void gptFailureCallsGeminiOnceAndReportsActualProvider() {
        List<String> calls = new ArrayList<>();
        LyricsAiClient.AiTextResult result = AiFallback.run("openai", true, new OperationScope(), p -> {
            calls.add(p); return p.equals("openai") ? error(p) : ok();
        });
        assertEquals(Arrays.asList("openai", "gemini"), calls);
        assertTrue(result.isSuccess()); assertEquals("gemini", result.usedProvider); assertFalse(result.routingNotice.isEmpty());
    }
    @Test public void missingBackupKeyDoesNotCallGemini() {
        List<String> calls = new ArrayList<>();
        AiFallback.run("openai", false, new OperationScope(), p -> { calls.add(p); return error(p); });
        assertEquals(Arrays.asList("openai"), calls);
    }
    @Test public void bothFailuresExplainBothProvidersWithoutLooping() {
        List<String> calls = new ArrayList<>();
        LyricsAiClient.AiTextResult result = AiFallback.run("openai", true, new OperationScope(), p -> { calls.add(p); return error(p); });
        assertEquals(2, calls.size()); assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("OpenAI (GPT):")); assertTrue(result.getErrorMessage().contains("Gemini:"));
    }
    @Test public void selectingGeminiDoesNotPreferGpt() {
        List<String> calls = new ArrayList<>();
        AiFallback.run("gemini", true, new OperationScope(), p -> { calls.add(p); return error(p); });
        assertEquals(Arrays.asList("gemini"), calls);
    }
    @Test public void cancellationPreventsBackupRequest() {
        OperationScope scope = new OperationScope(); List<String> calls = new ArrayList<>();
        AiFallback.run("openai", true, scope, p -> { calls.add(p); scope.cancel(); return error(p); });
        assertEquals(1, calls.size());
    }
    @Test public void explicitRefusalDoesNotTriggerCrossProviderBypass() {
        List<String> calls = new ArrayList<>();
        AiFallback.run("openai", true, new OperationScope(), p -> {
            calls.add(p); return new LyricsAiClient.AiTextResult(AiFailure.response("SAFETY"));
        });
        assertEquals(1, calls.size());
    }
}
