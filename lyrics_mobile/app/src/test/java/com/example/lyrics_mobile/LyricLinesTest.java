package com.example.lyrics_mobile;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class LyricLinesTest {
    @Test public void restoresIdsRegardlessOfJsonOrderAndKeepsBlankStanzas() {
        String[] result = LyricLines.parse("{\"lines\":[{\"id\":2,\"text\":\"둘\"},{\"id\":0,\"text\":\"하나\"}]}", Arrays.asList(0,2), 3);
        assertEquals("하나", result[0]); assertNull(result[1]); assertEquals("둘", result[2]);
        assertEquals("하나\n\n둘", LyricLines.join(new String[]{result[0], "", result[2]}));
    }
    @Test(expected=RuntimeException.class) public void rejectsDuplicateIds() {
        LyricLines.parse("{\"lines\":[{\"id\":0,\"text\":\"a\"},{\"id\":0,\"text\":\"b\"}]}", Arrays.asList(0,1),2);
    }
    @Test(expected=RuntimeException.class) public void rejectsMissingRows() {
        LyricLines.parse("{\"lines\":[{\"id\":0,\"text\":\"a\"}]}", Arrays.asList(0,1),2);
    }
    @Test(expected=RuntimeException.class) public void rejectsEmbeddedLineBreak() {
        LyricLines.parse("{\"lines\":[{\"id\":0,\"text\":\"a\\nb\"}]}", Arrays.asList(0),1);
    }
    @Test public void supportsFencedJsonWithoutCountingFenceAsLyrics() {
        assertEquals("a", LyricLines.parse("```json\n{\"lines\":[{\"id\":0,\"text\":\"a\"}]}\n```", Arrays.asList(0),1)[0]);
    }
    @Test public void gpt5MiniExplicitlyUsesMinimalReasoningButOtherFamiliesAreUntouched() {
        assertEquals("minimal", OpenAiClient.requestBody("test", "gpt-5-mini").getAsJsonObject("reasoning").get("effort").getAsString());
        assertFalse(OpenAiClient.requestBody("test", "gpt-4.1-mini").has("reasoning"));
        assertFalse(OpenAiClient.requestBody("test", "gpt-5.1").has("reasoning"));
    }
}
