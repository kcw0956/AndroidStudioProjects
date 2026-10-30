package com.codex.aitimeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class GeminiTimelineClient {
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build();

    public static class Result {
        public final boolean success;
        public final String text;

        public Result(boolean success, String text) {
            this.success = success;
            this.text = text == null ? "" : text.trim();
        }
    }

    public Result testModel(String apiKey, String modelId) {
        if (isBlank(apiKey)) {
            return new Result(false, "Gemini API 키를 입력해 주세요.");
        }
        if (isBlank(modelId)) {
            return new Result(false, "모델 ID를 입력해 주세요.");
        }
        String endpoint = BASE_URL + "/models/" + urlEncode(modelId.trim()) + "?key=" + urlEncode(apiKey.trim());
        Request request = new Request.Builder().url(endpoint).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                return new Result(false, "모델 확인 실패: " + response.code() + " " + parseError(body));
            }
            return new Result(true, modelId + " 모델을 사용할 수 있습니다.");
        } catch (Exception e) {
            return new Result(false, safeMessage(e));
        }
    }

    public Result listModels(String apiKey) {
        if (isBlank(apiKey)) {
            return new Result(false, "Gemini API 키를 먼저 입력해 주세요.");
        }
        String endpoint = BASE_URL + "/models?key=" + urlEncode(apiKey.trim());
        Request request = new Request.Builder().url(endpoint).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                return new Result(false, "모델 목록 실패: " + response.code() + " " + parseError(body));
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray models = root.has("models") ? root.getAsJsonArray("models") : new JsonArray();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < models.size(); i++) {
                JsonObject model = models.get(i).getAsJsonObject();
                String name = model.has("name") ? model.get("name").getAsString().replace("models/", "") : "";
                if (supportsGenerateContent(model) && !name.isEmpty()) {
                    names.add(name);
                }
                if (names.size() >= 12) {
                    break;
                }
            }
            return new Result(true, names.isEmpty()
                    ? "generateContent를 지원하는 모델을 찾지 못했습니다."
                    : "사용 가능 모델:\n" + joinLines(names));
        } catch (Exception e) {
            return new Result(false, safeMessage(e));
        }
    }

    public Result summarizeTimeline(String apiKey, String modelId, List<TimelineEvent> events) {
        if (isBlank(apiKey)) {
            return new Result(false, "Gemini API 키를 설정해 주세요.");
        }
        if (events == null || events.isEmpty()) {
            return new Result(false, "요약할 타임라인 이벤트가 없습니다.");
        }

        String prompt = buildPrompt(events);
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(textPart);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject body = new JsonObject();
        body.add("contents", contents);

        String selectedModel = isBlank(modelId) ? "gemini-3-flash" : modelId.trim();
        String endpoint = BASE_URL + "/models/" + urlEncode(selectedModel)
                + ":generateContent?key=" + urlEncode(apiKey.trim());
        Request request = new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                return new Result(false, "요약 실패: " + response.code() + " " + parseError(responseBody));
            }
            return new Result(true, extractText(responseBody));
        } catch (Exception e) {
            return new Result(false, safeMessage(e));
        }
    }

    private String buildPrompt(List<TimelineEvent> events) {
        StringBuilder builder = new StringBuilder();
        builder.append("너는 개인 생산성 타임라인 요약기다. 아래 이벤트는 로컬 PrivacyGuard가 민감정보를 마스킹한 데이터다.\n");
        builder.append("규칙: 개인정보를 추측하지 말고, 원문에 없는 세부 내용을 만들지 말고, 한국어로 짧게 요약해라.\n");
        builder.append("출력 형식:\n1. 오늘의 흐름 3줄\n2. 시간대별 타임라인 5개 이하\n3. 집중/분산 인사이트 2개\n\n");
        int count = Math.min(events.size(), 60);
        for (int i = count - 1; i >= 0; i--) {
            TimelineEvent event = PrivacyGuard.sanitize(events.get(i));
            if (event == null) {
                continue;
            }
            builder.append("- ")
                    .append(formatTime(event.timestamp))
                    .append(" | ")
                    .append(event.source)
                    .append(" | ")
                    .append(event.title)
                    .append(" | ")
                    .append(event.summary)
                    .append("\n");
        }
        return builder.toString();
    }

    private String extractText(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) {
                return "Gemini 응답에 요약 텍스트가 없습니다.";
            }
            JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            JsonArray parts = content.getAsJsonArray("parts");
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                JsonObject part = parts.get(i).getAsJsonObject();
                if (part.has("text")) {
                    builder.append(part.get("text").getAsString()).append("\n");
                }
            }
            return builder.toString().trim();
        } catch (Exception e) {
            return "Gemini 응답을 읽지 못했습니다.";
        }
    }

    private boolean supportsGenerateContent(JsonObject model) {
        if (!model.has("supportedGenerationMethods")) {
            return false;
        }
        JsonArray methods = model.getAsJsonArray("supportedGenerationMethods");
        for (int i = 0; i < methods.size(); i++) {
            if ("generateContent".equals(methods.get(i).getAsString())) {
                return true;
            }
        }
        return false;
    }

    private String parseError(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            return error == null ? body : error.get("message").getAsString();
        } catch (Exception ignored) {
            return body;
        }
    }

    private String joinLines(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            builder.append("- ").append(value).append("\n");
        }
        return builder.toString().trim();
    }

    private String formatTime(long timestamp) {
        return new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(timestamp));
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? "Gemini 요청 중 오류가 발생했습니다." : message;
    }
}
