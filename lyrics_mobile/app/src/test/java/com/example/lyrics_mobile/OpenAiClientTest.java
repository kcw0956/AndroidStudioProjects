package com.example.lyrics_mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class OpenAiClientTest {
    private static String completed(String content) {
        return "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\",\"status\":\"completed\",\"content\":" + content + "}]}";
    }
    @Test public void parsesAllTextPartsIgnoringReasoning() {
        LyricsAiClient.AiTextResult result = OpenAiClient.parseResponse(completed("[{\"type\":\"output_text\",\"text\":\"first\\n\"},{\"type\":\"output_text\",\"text\":\"second\"}]"));
        assertTrue(result.isSuccess()); assertEquals("first\nsecond", result.getText());
    }
    @Test public void doesNotAcceptPartialOutput() {
        LyricsAiClient.AiTextResult result = OpenAiClient.parseResponse("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}");
        assertFalse(result.isSuccess()); assertEquals("MAX_TOKENS", result.getFailure().code);
        assertEquals("openai", result.getFailure().provider);
    }
    @Test public void refusalIsNotSuccessfulText() {
        LyricsAiClient.AiTextResult result = OpenAiClient.parseResponse(completed("[{\"type\":\"refusal\",\"refusal\":\"No\"}]"));
        assertFalse(result.isSuccess()); assertEquals("REFUSAL", result.getFailure().code);
    }
    @Test public void emptyAndMalformedResultsFail() {
        assertFalse(OpenAiClient.parseResponse("not-json").isSuccess());
        assertFalse(OpenAiClient.parseResponse(completed("[]")).isSuccess());
        assertFalse(OpenAiClient.parseResponse("{\"status\":\"queued\"}").isSuccess());
    }
    @Test public void billingAndRateLimitsAreDistinct() {
        assertEquals(AiFailure.Action.BILLING, OpenAiClient.httpFailure(429, "{\"error\":{\"code\":\"insufficient_quota\"}}").action);
        assertEquals(AiFailure.Action.RETRY, OpenAiClient.httpFailure(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}").action);
    }
    @Test public void secretsAreNotEchoedInFailures() {
        AiFailure failure = OpenAiClient.httpFailure(401, "{\"error\":{\"message\":\"secret-do-not-show\"}}");
        assertEquals("KEY_INVALID", failure.code); assertFalse(failure.message.contains("secret-do-not-show"));
        assertEquals("openai", failure.provider);
    }
    @Test public void requestDisablesServerStorageAndUsesResponsesShape() {
        com.google.gson.JsonObject body = OpenAiClient.requestBody("test", "gpt-4.1-mini");
        assertFalse(body.get("store").getAsBoolean()); assertEquals("test", body.get("input").getAsString());
        assertFalse(body.has("generationConfig"));
    }
    @Test public void modelListExcludesNonTextEndpoints() {
        assertTrue(OpenAiClient.isTextCandidate("gpt-4.1-mini"));
        assertFalse(OpenAiClient.isTextCandidate("gpt-audio"));
        assertFalse(OpenAiClient.isTextCandidate("gpt-4o-transcribe"));
        assertFalse(OpenAiClient.isTextCandidate("gpt-image-1"));
        assertFalse(OpenAiClient.isTextCandidate("gpt-3.5-turbo-instruct"));
    }
}
