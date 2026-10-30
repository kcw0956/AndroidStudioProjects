package com.codex.aitimeline;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class PrivacyGuard {
    private static final Set<String> PRIVATE_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.settings",
            "com.google.android.apps.authenticator2",
            "com.google.android.gms",
            "com.google.android.googlequicksearchbox",
            "viva.republica.toss"
    ));

    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(\\+?\\d[\\d\\s.-]{7,}\\d)");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");
    private static final Pattern LONG_SECRET = Pattern.compile("\\b[A-Za-z0-9_=-]{24,}\\b");
    private static final Pattern KOREAN_RRN = Pattern.compile("\\b\\d{6}[- ]?[1-4]\\d{6}\\b");

    public static TimelineEvent sanitize(TimelineEvent event) {
        if (event == null) {
            return null;
        }
        if (isPrivatePackage(event.packageName)) {
            return new TimelineEvent(
                    event.timestamp,
                    "Private",
                    event.packageName,
                    "비공개 보호",
                    "민감 가능성이 높은 앱이라 세부 내용 없이 시간만 기록했습니다."
            );
        }
        return new TimelineEvent(
                event.timestamp,
                event.source,
                event.packageName,
                mask(event.title),
                mask(event.summary)
        );
    }

    public static String mask(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String value = text;
        value = EMAIL.matcher(value).replaceAll("[email]");
        value = PHONE.matcher(value).replaceAll("[phone]");
        value = CARD.matcher(value).replaceAll("[card]");
        value = KOREAN_RRN.matcher(value).replaceAll("[id]");
        value = LONG_SECRET.matcher(value).replaceAll("[secret]");
        value = AppNameResolver.scrubKnownPackageNames(value);
        return value.trim();
    }

    private static boolean isPrivatePackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        String lower = packageName.toLowerCase();
        if (PRIVATE_PACKAGES.contains(lower)) {
            return true;
        }
        if (lower.contains("toss")) {
            return true;
        }
        return lower.contains("bank")
                || lower.contains("pay")
                || lower.contains("auth")
                || lower.contains("password")
                || lower.contains("wallet");
    }
}
