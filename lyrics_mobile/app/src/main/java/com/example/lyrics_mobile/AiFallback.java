package com.example.lyrics_mobile;

/** Selected provider always runs first, including its cache lookup. No fallback loop. */
final class AiFallback {
    interface Attempt { LyricsAiClient.AiTextResult run(String provider); }

    static LyricsAiClient.AiTextResult run(String primary, boolean geminiConfigured,
                                          OperationScope scope, Attempt attempt) {
        LyricsAiClient.AiTextResult first = attempt.run(primary);
        first.usedProvider = primary;
        if (first.isSuccess() || !"openai".equals(primary) || !geminiConfigured
                || scope.isCancelled() || Thread.currentThread().isInterrupted()
                || first.getFailure().action == AiFailure.Action.CLOSE) return first;
        scope.progress("GPT 요청을 완료하지 못해 Gemini로 전환하고 있어요");
        LyricsAiClient.AiTextResult second = attempt.run("gemini");
        second.usedProvider = "gemini";
        if (second.isSuccess()) {
            second.routingNotice = "GPT 요청 실패: " + first.getFailure().title + " · Gemini로 대신 생성했어요.";
            return second;
        }
        AiFailure failure = second.getFailure();
        return new LyricsAiClient.AiTextResult(new AiFailure("PROVIDERS_FAILED", "두 AI 모두 요청을 완료하지 못했어요",
                "OpenAI (GPT): " + first.getFailure().message + "\n\nGemini: " + failure.message,
                failure.action == AiFailure.Action.CLOSE ? AiFailure.Action.CLOSE : AiFailure.Action.SETTINGS).forProvider("gemini"));
    }
}
