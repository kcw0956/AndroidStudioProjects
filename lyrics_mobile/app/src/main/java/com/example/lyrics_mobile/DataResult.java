package com.example.lyrics_mobile;

public final class DataResult<T> {
    public final T value;
    public final AiFailure failure;
    private DataResult(T value, AiFailure failure) { this.value = value; this.failure = failure; }
    public boolean isSuccess() { return failure == null; }
    public static <T> DataResult<T> ok(T value) { return new DataResult<>(value, null); }
    public static <T> DataResult<T> fail(AiFailure error) { return new DataResult<>(null, error); }
    public static AiFailure missing(String message) {
        return new AiFailure("NOT_FOUND", "찾지 못했어요", message, AiFailure.Action.RETRY);
    }
    public static AiFailure service(int status, String service) {
        if (status == 503) return new AiFailure("SERVICE_503", service + "가 일시적으로 이용 불가예요",
                "서버가 요청을 처리할 수 없다고 응답했어요 (503). 과부하·점검 등이 원인일 수 있으며, 가사가 없다는 뜻은 아니에요.\n\n잠시 기다린 뒤 다시 시도해 주세요. 계속되면 나중에 이용해 주세요. AI 키·잔액 문제가 아니므로 결제할 필요가 없어요.", AiFailure.Action.RETRY);
        if (status == 502 || status == 504) return new AiFailure("SERVICE_" + status, service + "의 응답이 지연되고 있어요",
                "서버 사이의 연결 또는 응답 대기에 문제가 생겼어요 (" + status + "). 잠시 후 다시 시도해 주세요. 계속되면 나중에 다시 이용해 주세요.", AiFailure.Action.RETRY);
        return new AiFailure("SERVICE_" + status, service + "에 연결하지 못했어요",
                status == 429 ? "조회 한도에 도달했어요. 잠시 후 다시 시도해 주세요."
                        : "서비스 응답에 문제가 있어요. 잠시 후 다시 시도해 주세요. (오류 " + status + ")", AiFailure.Action.RETRY);
    }
}
