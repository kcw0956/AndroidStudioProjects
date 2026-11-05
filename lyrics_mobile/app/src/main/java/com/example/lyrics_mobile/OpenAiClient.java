package com.example.lyrics_mobile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** OpenAI REST transport. Prompts and line validation are shared with Gemini. */
final class OpenAiClient {
    private static final String BASE = "https://api.openai.com/v1";
    private final OperationScope scope;
    private static final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build();
    OpenAiClient(OperationScope scope) { this.scope = scope; }
    private static LyricsAiClient.AiTextResult fail(AiFailure failure) {
        return new LyricsAiClient.AiTextResult(failure.forProvider("openai"));
    }
    static JsonObject requestBody(String prompt, String model) {
        JsonObject body = new JsonObject();
        String selected = model == null || model.trim().isEmpty() ? "gpt-4.1-mini" : model.trim();
        body.addProperty("model", selected);
        if (selected.matches("gpt-5(?:-mini|-nano)?(?:-\\d{4}-\\d{2}-\\d{2})?")) {
            JsonObject reasoning = new JsonObject(); reasoning.addProperty("effort", "minimal"); body.add("reasoning", reasoning);
        }
        body.addProperty("input", prompt);
        body.addProperty("max_output_tokens", 12000);
        body.addProperty("store", false);
        return body;
    }
    LyricsAiClient.AiTextResult generate(String prompt, String rawKeys, String model) {
        List<String> keys = AppSettings.parseApiKeys(rawKeys);
        if (keys.isEmpty()) return fail(AiFailure.missingKey("openai"));
        AiFailure last = AiFailure.response("EMPTY");
        for (String key : keys) {
            long started = System.nanoTime();
            try {
                scope.check();
                Request request = new Request.Builder().url(BASE + "/responses").header("Authorization", "Bearer " + key)
                        .post(RequestBody.create(requestBody(prompt, model).toString(), MediaType.parse("application/json; charset=utf-8"))).build();
                try (Response response = scope.execute(client.newCall(request))) {
                    String body = response.body() == null ? "" : response.body().string();
                    if (response.isSuccessful()) {
                        LyricsAiClient.AiTextResult result = parseResponse(body);
                        scope.record("openai", model, result.isSuccess() ? "HTTP_200" : result.getFailure().code, (System.nanoTime() - started) / 1000000);
                        return result;
                    }
                    last = httpFailure(response.code(), body);
                    scope.record("openai", model, "HTTP_" + response.code() + ":" + last.code, (System.nanoTime() - started) / 1000000);
                    // No retry of ambiguous network failures or partial outputs: avoid duplicate paid generations.
                    if (response.code() != 401 && response.code() != 403 && response.code() != 429) return fail(last);
                }
            } catch (Exception e) {
                AiFailure failure = AiFailure.exception(e);
                scope.record("openai", model, failure.code, (System.nanoTime() - started) / 1000000);
                return fail(failure);
            }
        }
        return fail(last);
    }
    static LyricsAiClient.AiTextResult parseResponse(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String status = string(root, "status");
            if (!"completed".equals(status)) {
                if (root.has("incomplete_details") && root.get("incomplete_details").isJsonObject()) {
                    String reason = string(root.getAsJsonObject("incomplete_details"), "reason");
                    if ("max_output_tokens".equals(reason)) return fail(AiFailure.response("MAX_TOKENS"));
                    if ("content_filter".equals(reason)) return fail(AiFailure.response("SAFETY"));
                }
                if (root.has("error") && root.get("error").isJsonObject()) {
                    String code = string(root.getAsJsonObject("error"), "code");
                    if ("server_error".equals(code)) return fail(httpFailure(500, body));
                    if ("rate_limit_exceeded".equals(code)) return fail(httpFailure(429, body));
                }
                return fail(AiFailure.response("INCOMPLETE"));
            }
            StringBuilder text = new StringBuilder();
            if (root.has("output") && root.get("output").isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray("output")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject item = element.getAsJsonObject();
                    if (!"message".equals(string(item, "type")) || !item.has("content") || !item.get("content").isJsonArray()) continue;
                    if (!"completed".equals(string(item, "status"))) return fail(AiFailure.response("INCOMPLETE"));
                    for (JsonElement part : item.getAsJsonArray("content")) {
                        if (!part.isJsonObject()) continue;
                        JsonObject content = part.getAsJsonObject();
                        if ("refusal".equals(string(content, "type"))) return fail(new AiFailure("REFUSAL", "AI가 요청을 거절했어요",
                                "제공자가 이 요청의 결과를 제공하지 않았어요. 원본 가사는 계속 볼 수 있어요.", AiFailure.Action.CLOSE));
                        if ("output_text".equals(string(content, "type"))) text.append(string(content, "text"));
                    }
                }
            }
            if (text.toString().trim().isEmpty()) return fail(AiFailure.response("EMPTY"));
            return new LyricsAiClient.AiTextResult(true, text.toString(), "");
        } catch (RuntimeException e) { return fail(AiFailure.response("FORMAT")); }
    }
    static AiFailure httpFailure(int status, String body) {
        String code = "", type = "";
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            code = string(error, "code"); type = string(error, "type");
        } catch (RuntimeException ignored) { /* Use HTTP status for non-JSON gateway errors. */ }
        AiFailure failure;
        if ("insufficient_quota".equals(code) || "insufficient_quota".equals(type) || status == 402
                || code.startsWith("billing_")) {
            failure = new AiFailure("BILLING", "결제·사용 한도를 확인해 주세요", "OpenAI API 프로젝트의 크레딧 또는 사용 한도 문제예요. 결제 상태·잔액·예산을 확인해 주세요.", AiFailure.Action.BILLING);
        } else if (status == 401 || "invalid_api_key".equals(code)) {
            failure = new AiFailure("KEY_INVALID", "OpenAI API 키를 사용할 수 없어요", "키가 잘못되었거나 해제되었어요. OpenAI에서 키 상태를 확인하고 새 키를 입력해 주세요.", AiFailure.Action.SETTINGS);
        } else if (status == 429) {
            failure = new AiFailure("RATE_LIMIT", "요청 속도 제한에 걸렸어요", "잠시 후 다시 시도해 주세요. 계속되면 OpenAI 프로젝트의 요청·토큰 한도를 확인해 주세요.", AiFailure.Action.RETRY);
        } else if (status == 403) {
            failure = new AiFailure("PERMISSION", "OpenAI API 접근이 제한됐어요", "프로젝트 권한, 키의 사용 제한 또는 지원 지역을 확인해 주세요.", AiFailure.Action.SETTINGS);
        } else if (status == 404 || "model_not_found".equals(code)) {
            failure = new AiFailure("MODEL", "선택한 모델을 사용할 수 없어요", "프로젝트에서 사용할 수 있는 텍스트 모델을 선택하고 연결 테스트를 실행해 주세요.", AiFailure.Action.SETTINGS);
        } else if ("context_length_exceeded".equals(code)) {
            failure = new AiFailure("CONTEXT", "가사가 모델의 입력 한도를 넘었어요", "더 긴 입력을 지원하는 모델을 선택해 주세요.", AiFailure.Action.SETTINGS);
        } else if ("content_policy_violation".equals(code)) {
            failure = AiFailure.response("SAFETY");
        } else if (status == 408 || status >= 500) {
            failure = AiFailure.http(status, "");
        } else {
            failure = new AiFailure("HTTP_" + status, "OpenAI 요청이 거절됐어요", "선택한 모델이 Responses API 텍스트 생성을 지원하는지 확인해 주세요. (오류 " + status + ")", AiFailure.Action.SETTINGS);
        }
        return failure.forProvider("openai");
    }
    LyricsAiClient.ModelFetchResult models(String rawKeys) {
        List<LyricsAiClient.ModelOption> models = new ArrayList<>();
        List<String> keys = AppSettings.parseApiKeys(rawKeys);
        if (keys.isEmpty()) return new LyricsAiClient.ModelFetchResult(false, models, AiFailure.missingKey("openai").message);
        try {
            Request request = new Request.Builder().url(BASE + "/models").header("Authorization", "Bearer " + keys.get(0)).build();
            try (Response response = scope.execute(client.newCall(request))) {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) return new LyricsAiClient.ModelFetchResult(false, models, httpFailure(response.code(), body).message);
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                for (JsonElement element : root.getAsJsonArray("data")) {
                    String id = string(element.getAsJsonObject(), "id");
                    if (isTextCandidate(id)) models.add(new LyricsAiClient.ModelOption(id, id, "gpt-4.1-mini".equals(id)));
                }
                java.util.Collections.sort(models, (a,b) -> a.getId().compareTo(b.getId()));
                return new LyricsAiClient.ModelFetchResult(!models.isEmpty(), models, models.isEmpty()
                        ? "사용 가능한 GPT 텍스트 후보가 없어요. 모델 ID를 직접 입력할 수 있어요."
                        : "텍스트 모델 후보예요. 선택 후 연결 테스트로 사용 가능 여부를 확인해 주세요.");
            }
        } catch (Exception e) { return new LyricsAiClient.ModelFetchResult(false, models, AiFailure.exception(e).message); }
    }
    static boolean isTextCandidate(String id) {
        return id.startsWith("gpt-") && !id.matches(".*(audio|realtime|transcri|image|tts|search|instruct|codex).*");
    }
    private static String string(JsonObject object, String key) {
        return object == null || !object.has(key) || !object.get(key).isJsonPrimitive() ? "" : object.get(key).getAsString();
    }
}
