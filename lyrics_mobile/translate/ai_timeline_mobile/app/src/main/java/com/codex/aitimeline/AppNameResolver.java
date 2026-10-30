package com.codex.aitimeline;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class AppNameResolver {
    private static final Map<String, String> KNOWN_NAMES = buildKnownNames();

    public static String friendlyName(Context context, String packageName, String fallback) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return cleanFallback(fallback);
        }
        String normalized = packageName.trim().toLowerCase(Locale.US);
        if (KNOWN_NAMES.containsKey(normalized)) {
            return KNOWN_NAMES.get(normalized);
        }
        String partial = partialKnownName(normalized);
        if (!partial.isEmpty()) {
            return partial;
        }

        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                info = packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0));
            } else {
                info = packageManager.getApplicationInfo(packageName, 0);
            }
            CharSequence label = packageManager.getApplicationLabel(info);
            if (label != null && !label.toString().trim().isEmpty()) {
                return label.toString().trim();
            }
        } catch (Exception ignored) {
        }

        String cleanedFallback = cleanFallback(fallback);
        if (!cleanedFallback.isEmpty() && !looksLikePackageOrClass(cleanedFallback)) {
            return cleanedFallback;
        }
        return humanizePackage(normalized);
    }

    public static String category(String packageName) {
        if (packageName == null) {
            return "기타";
        }
        String lower = packageName.toLowerCase(Locale.US);
        if (lower.contains("toss") || lower.contains("bank") || lower.contains("pay") || lower.contains("wallet")) {
            return "금융";
        }
        if (lower.contains("chrome") || lower.contains("browser") || lower.contains("naver") || lower.contains("daum")) {
            return "웹 탐색";
        }
        if (lower.contains("youtube") || lower.contains("video") || lower.contains("netflix")) {
            return "영상";
        }
        if (lower.contains("kakao") || lower.contains("messag") || lower.contains("talk") || lower.contains("slack") || lower.contains("discord")) {
            return "커뮤니케이션";
        }
        if (lower.contains("calendar")) {
            return "일정";
        }
        if (lower.contains("docs") || lower.contains("notion") || lower.contains("office") || lower.contains("word") || lower.contains("excel")) {
            return "문서 작업";
        }
        if (lower.contains("instagram") || lower.contains("facebook") || lower.contains("twitter") || lower.contains("xhs")) {
            return "소셜";
        }
        if (lower.contains("map")) {
            return "지도";
        }
        return "기타";
    }

    public static boolean isSensitiveCategory(String packageName) {
        String category = category(packageName);
        return "금융".equals(category);
    }

    public static String scrubKnownPackageNames(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String scrubbed = text;
        for (Map.Entry<String, String> entry : KNOWN_NAMES.entrySet()) {
            scrubbed = scrubbed.replace(entry.getKey(), entry.getValue());
        }
        scrubbed = scrubbed.replace("viva.republica.toss", "토스");
        scrubbed = scrubbed.replace("com.android.chrome", "Chrome");
        scrubbed = scrubbed.replace("com.google.android.youtube", "YouTube");
        return scrubbed.trim();
    }

    public static boolean looksLikePackageOrClass(String value) {
        if (value == null) {
            return false;
        }
        String text = value.trim();
        return text.contains(".") && text.matches("[A-Za-z0-9_.$]+");
    }

    private static String partialKnownName(String packageName) {
        if (packageName.contains("toss")) {
            return "토스";
        }
        if (packageName.contains("kakao")) {
            return "카카오톡";
        }
        if (packageName.contains("youtube")) {
            return "YouTube";
        }
        if (packageName.contains("chrome")) {
            return "Chrome";
        }
        if (packageName.contains("instagram")) {
            return "Instagram";
        }
        if (packageName.contains("naver")) {
            return "네이버";
        }
        return "";
    }

    private static String humanizePackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return "알 수 없는 앱";
        }
        String[] parts = packageName.split("\\.");
        String last = parts.length == 0 ? packageName : parts[parts.length - 1];
        if (last.trim().isEmpty()) {
            return "알 수 없는 앱";
        }
        return last.substring(0, 1).toUpperCase(Locale.US) + last.substring(1);
    }

    private static String cleanFallback(String fallback) {
        return fallback == null ? "" : fallback.trim();
    }

    private static Map<String, String> buildKnownNames() {
        Map<String, String> names = new HashMap<>();
        names.put("viva.republica.toss", "토스");
        names.put("com.kakao.talk", "카카오톡");
        names.put("com.android.chrome", "Chrome");
        names.put("com.google.android.youtube", "YouTube");
        names.put("com.google.android.gm", "Gmail");
        names.put("com.google.android.apps.docs", "Google Docs");
        names.put("com.google.android.apps.maps", "Google Maps");
        names.put("com.instagram.android", "Instagram");
        names.put("com.nhn.android.search", "네이버");
        names.put("com.naver.line.android", "LINE");
        names.put("notion.id", "Notion");
        names.put("com.slack", "Slack");
        return names;
    }
}
