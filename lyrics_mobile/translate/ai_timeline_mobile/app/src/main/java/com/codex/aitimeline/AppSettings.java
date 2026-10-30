package com.codex.aitimeline;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class AppSettings {
    private static final String PREF_NAME = "ai_timeline_settings";
    private static final String KEY_GEMINI_API_KEY = "gemini_api_key";
    private static final String KEY_GEMINI_MODEL = "gemini_model";
    private static final String KEY_TRACKING_PAUSED = "tracking_paused";
    private static final String KEY_HIDDEN_PACKAGES = "hidden_packages";
    private static final String KEY_TODAY_MEMO = "today_memo";
    private static final String KEY_TODAY_MEMO_DATE = "today_memo_date";
    private static final String KEY_GOAL_LABEL = "goal_label";
    private static final String KEY_GOAL_MINUTES = "goal_minutes";
    private static final String DEFAULT_MODEL = "gemini-3-flash";

    private final SharedPreferences prefs;

    public AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveGeminiSettings(String apiKey, String modelId) {
        prefs.edit()
                .putString(KEY_GEMINI_API_KEY, clean(apiKey))
                .putString(KEY_GEMINI_MODEL, clean(modelId))
                .apply();
    }

    public String getGeminiApiKey() {
        return prefs.getString(KEY_GEMINI_API_KEY, "");
    }

    public String getGeminiModel() {
        String value = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_MODEL);
        return value == null || value.trim().isEmpty() ? DEFAULT_MODEL : value.trim();
    }

    public boolean hasGeminiApiKey() {
        return !getGeminiApiKey().trim().isEmpty();
    }

    public boolean isTrackingPaused() {
        return prefs.getBoolean(KEY_TRACKING_PAUSED, false);
    }

    public void setTrackingPaused(boolean paused) {
        prefs.edit().putBoolean(KEY_TRACKING_PAUSED, paused).apply();
    }

    public Set<String> getHiddenPackages() {
        Set<String> values = prefs.getStringSet(KEY_HIDDEN_PACKAGES, Collections.emptySet());
        return values == null ? new HashSet<>() : new HashSet<>(values);
    }

    public boolean isPackageHidden(String packageName) {
        return packageName != null && getHiddenPackages().contains(packageName);
    }

    public void hidePackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return;
        }
        Set<String> values = getHiddenPackages();
        values.add(packageName.trim());
        prefs.edit().putStringSet(KEY_HIDDEN_PACKAGES, values).apply();
    }

    public void clearHiddenPackages() {
        prefs.edit().remove(KEY_HIDDEN_PACKAGES).apply();
    }

    public String getTodayMemo() {
        String date = prefs.getString(KEY_TODAY_MEMO_DATE, "");
        if (!todayKey().equals(date)) {
            return "";
        }
        return prefs.getString(KEY_TODAY_MEMO, "");
    }

    public void saveTodayMemo(String memo) {
        prefs.edit()
                .putString(KEY_TODAY_MEMO, clean(memo))
                .putString(KEY_TODAY_MEMO_DATE, todayKey())
                .apply();
    }

    public String getGoalLabel() {
        String value = prefs.getString(KEY_GOAL_LABEL, "회수 목표");
        return value == null || value.trim().isEmpty() ? "회수 목표" : value.trim();
    }

    public int getGoalMinutes() {
        return prefs.getInt(KEY_GOAL_MINUTES, 120);
    }

    public void saveGoal(String label, int minutes) {
        prefs.edit()
                .putString(KEY_GOAL_LABEL, clean(label))
                .putInt(KEY_GOAL_MINUTES, Math.max(10, minutes))
                .apply();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(new Date());
    }
}
