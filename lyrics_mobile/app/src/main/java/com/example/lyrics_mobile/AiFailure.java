package com.example.lyrics_mobile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InterruptedIOException;
import java.net.UnknownHostException;
import java.util.Locale;

/** User-facing errors never include raw responses, request URLs, or credentials. */
public final class AiFailure {
    public enum Action { SETTINGS, BILLING, RETRY, CLOSE }

    public final String provider;
    public final String code;
    public final String title;
    public final String message;
    public final Action action;

    public AiFailure(String code, String title, String message, Action action) {
        this.provider = "gemini";
        this.code = code;
        this.title = title;
        this.message = message;
        this.action = action;
    }

    private AiFailure(AiFailure original, String provider) {
        this.provider = provider; this.code = original.code; this.title = original.title;
        this.message = original.message; this.action = original.action;
    }
    public AiFailure forProvider(String provider) { return new AiFailure(this, provider); }
    public static AiFailure missingKey(String provider) {
        return new AiFailure("KEY_MISSING", "API 키가 필요해요", "설정에서 " + AppSettings.providerLabel(provider) + " API 키를 입력해 주세요.", Action.SETTINGS).forProvider(provider);
    }

    public static AiFailure missingKey() {
        return new AiFailure("KEY_MISSING", "API 키가 필요해요", "설정에서 Gemini API 키를 입력해 주세요.", Action.SETTINGS);
    }

    public static AiFailure response(String code) {
        if ("LINE_MISMATCH".equals(code)) return new AiFailure(code, "가사 줄을 맞추지 못했어요", "AI 응답의 줄 번호가 빠지거나 중복됐어요. 연결 오류는 아니에요. 다시 시도하거나 다른 모델을 선택해 주세요.", Action.RETRY);
        if ("MAX_TOKENS".equals(code)) {
            return new AiFailure(code, "결과가 중간에 끊겼어요", "모델의 출력 한도에 도달했어요. 다른 모델을 선택해 주세요.", Action.SETTINGS);
        }
        if ("RECITATION".equals(code)) {
            return new AiFailure(code, "AI가 생성을 중단했어요", "기존 자료와의 유사성 때문에 제공자가 출력을 제한했어요. 원본 가사는 계속 볼 수 있어요.", Action.CLOSE);
        }
        if ("SAFETY".equals(code)
                || "BLOCKLIST".equals(code) || "PROHIBITED_CONTENT".equals(code)) {
            return new AiFailure(code, "AI가 생성을 제한했어요", "이 가사에 대한 결과가 제공자의 콘텐츠 정책으로 제한되었어요. 원본 가사는 계속 볼 수 있어요.", Action.CLOSE);
        }
        return new AiFailure("RESPONSE_INVALID", "결과를 받지 못했어요", "AI 응답이 비어 있거나 가사 형식과 맞지 않아요. 다시 시도해 주세요.", Action.RETRY);
    }

    public static AiFailure http(int status, String body) {
        String details = "";
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null) details = error.toString().toLowerCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
            // Status-only fallback for non-JSON gateway errors.
        }
        if (details.contains("api_key_expired") || details.contains("api key expired")) {
            return new AiFailure("KEY_EXPIRED", "API 키가 만료되었어요", "Google AI Studio에서 새 키를 발급받아 설정에 입력해 주세요.", Action.SETTINGS);
        }
        if (details.contains("leaked") || details.contains("api_key_invalid")
                || details.contains("api key not valid") || details.contains("api_key_not_found")
                || details.contains("api key was blocked") || status == 401) {
            return new AiFailure("KEY_INVALID", "API 키를 사용할 수 없어요", "키가 잘못되었거나 차단되었어요. Google AI Studio에서 키 상태를 확인하고 교체해 주세요.", Action.SETTINGS);
        }
        if (status == 402 || details.contains("billing_disabled") || details.contains("billing_not_active")
                || details.contains("insufficient credits")
                || details.contains("credit balance") || details.contains("outstanding balance")
                || details.contains("payment required") || details.contains("prepay")) {
            return new AiFailure("BILLING", "결제·크레딧 확인이 필요해요", "제공자가 결제 또는 크레딧 문제로 요청을 거절했어요. AI Studio에서 결제 상태와 잔액을 확인해 주세요.", Action.BILLING);
        }
        if (details.contains("free tier is not available") || details.contains("free tier unavailable")) {
            return new AiFailure("PLAN", "이 프로젝트에서 무료 사용이 제한돼요", "AI Studio에서 프로젝트의 요금제와 사용 가능 여부를 확인해 주세요.", Action.BILLING);
        }
        if (status == 429 || details.contains("insufficient_quota")) {
            boolean daily = details.contains("perday") || details.contains("per_day") || details.contains("daily");
            return new AiFailure(daily ? "DAILY_QUOTA" : "QUOTA", daily ? "일일 사용 한도에 도달했어요" : "사용량 제한에 걸렸어요",
                    daily ? "한도가 초기화된 뒤 다시 시도하거나 AI Studio에서 사용 한도를 확인해 주세요."
                            : "요청 속도 또는 사용 한도를 초과했어요. 잠시 후 다시 시도하고, 계속되면 AI Studio에서 한도와 요금제를 확인해 주세요.", Action.BILLING);
        }
        if (status == 403 || details.contains("permission_denied")) {
            return new AiFailure("PERMISSION", "API 접근이 허용되지 않았어요", "키의 사용 제한, 프로젝트 권한 또는 지역 제한을 확인해 주세요. 이 응답만으로 잔액 부족 여부는 확인할 수 없어요.", Action.SETTINGS);
        }
        if (status == 404) {
            return new AiFailure("MODEL", "선택한 모델을 사용할 수 없어요", "설정에서 모델 목록을 새로고침하고 사용 가능한 모델을 선택해 주세요.", Action.SETTINGS);
        }
        if (status == 408 || status == 504) return timeout();
        if (status >= 500) {
            return new AiFailure("SERVER", "AI 서버가 응답하지 않아요", "제공자 서버에 일시적인 문제가 있어요. 잠시 후 다시 시도해 주세요.", Action.RETRY);
        }
        return new AiFailure("HTTP_" + status, "요청이 거절되었어요", "모델 설정을 확인해 주세요. 계속되면 앱 업데이트가 필요할 수 있어요. (오류 " + status + ")", Action.SETTINGS);
    }

    public static AiFailure exception(Exception error) {
        if (error instanceof InterruptedIOException) return timeout();
        if (error instanceof UnknownHostException || error instanceof java.io.IOException) {
            return new AiFailure("NETWORK", "인터넷 연결을 확인해 주세요", "AI 서버에 연결하지 못했어요. Wi-Fi·모바일 데이터 연결을 확인하고 다시 시도해 주세요.", Action.RETRY);
        }
        return response("RESPONSE_INVALID");
    }

    private static AiFailure timeout() {
        return new AiFailure("TIMEOUT", "응답 대기 시간이 지났어요", "번역 결과를 제시간에 받지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.", Action.RETRY);
    }
}
