package com.example.lyrics_mobile;

public final class RecognitionConfig {
    private RecognitionConfig() {}
    public static String validate(String host, String key, String secret) {
        String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        if (!h.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.acrcloud\\.com")) return "Host에는 https:// 없이 ACRCloud 서버 주소를 입력해 주세요.";
        if (key.trim().isEmpty() || secret.trim().isEmpty()) return "Access Key와 Access Secret을 모두 입력해 주세요.";
        return "";
    }
}
