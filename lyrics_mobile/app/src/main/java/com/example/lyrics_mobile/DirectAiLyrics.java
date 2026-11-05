package com.example.lyrics_mobile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** AI output is a labelled, unverified candidate, never a verified source or timing track. */
final class DirectAiLyrics {
    static AiFailure failure(AiFailure failure) {
        if (java.util.Arrays.asList("REFUSAL", "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT").contains(failure.code))
            return new AiFailure(failure.code, "AI가 가사를 제공하지 않았어요",
                    "제공자가 이 요청의 결과를 제한했어요. 직접 입력하거나 나중에 가사 서버를 다시 조회해 주세요. 요청 사용료는 발생할 수 있어요.", AiFailure.Action.CLOSE);
        return failure;
    }
    static DataResult<LrcLibClient.Lyrics> parse(String text, SongInfo song, String provider) {
        try {
            if (text == null || text.length() > 100000) return invalid();
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            String status = field(json, "status");
            if ("restricted".equals(status)) return DataResult.fail(new AiFailure("AI_LYRICS_RESTRICTED", "AI가 가사를 제공하지 않았어요",
                    "제공자의 콘텐츠·저작권 정책으로 제한됐어요. 직접 입력하거나 가사 서버가 복구된 뒤 다시 조회해 주세요. 요청 사용료는 발생할 수 있어요.", AiFailure.Action.RETRY));
            if ("unavailable".equals(status)) return DataResult.fail(new AiFailure("AI_LYRICS_UNAVAILABLE", "AI도 가사를 확인하지 못했어요",
                    "이 곡의 정확한 가사를 제공하지 못했어요. 직접 입력하거나 나중에 가사 서버를 다시 조회해 주세요. 요청 사용료는 발생할 수 있어요.", AiFailure.Action.RETRY));
            if (!"ok".equals(status) || !song.getTitle().equals(field(json, "title"))
                    || !song.getArtist().equals(field(json, "artist"))) return invalid();
            String lyrics = field(json, "lyrics").trim();
            if (lyrics.isEmpty() || lyrics.length() > 60000) return invalid();
            LrcLibClient.Lyrics result = new LrcLibClient.Lyrics();
            result.plain = lyrics; result.aiSupplied = true;
            result.source = AppSettings.providerLabel(provider) + " AI 응답 · 미검증";
            result.notice = "AI가 반환한 가사 후보예요. 실제 원문과 다르거나 일부가 빠질 수 있어요. 웹·제공처 검증 및 싱크 정보는 없어요.";
            return DataResult.ok(result);
        } catch (RuntimeException e) { return invalid(); }
    }
    private static String field(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException();
        return json.get(key).getAsString();
    }
    private static DataResult<LrcLibClient.Lyrics> invalid() {
        return DataResult.fail(new AiFailure("AI_LYRICS_INVALID", "AI 응답을 가사로 사용할 수 없어요",
                "곡 정보나 응답 형식이 맞지 않아 저장하지 않았어요. 직접 입력하거나 가사 서버를 다시 조회해 주세요. 자동 유료 재요청은 하지 않아요.", AiFailure.Action.RETRY));
    }
}
