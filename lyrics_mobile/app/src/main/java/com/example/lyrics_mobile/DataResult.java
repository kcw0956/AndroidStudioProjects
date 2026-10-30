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
        return new AiFailure("SERVICE_" + status, service + "에 연결하지 못했어요",
                status == 429 ? "조회 한도에 도달했어요. 잠시 후 다시 시도해 주세요."
                        : "서비스 응답에 문제가 있어요. 잠시 후 다시 시도해 주세요. (오류 " + status + ")", AiFailure.Action.RETRY);
    }
}
