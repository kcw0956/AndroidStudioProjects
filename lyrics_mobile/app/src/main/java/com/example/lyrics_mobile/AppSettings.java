package com.example.lyrics_mobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class AppSettings {
    private static final String PREF_NAME = "lyrics_mobile_prefs";
    private static final String KEY_GEMINI_API_KEYS = "gemini_api_keys";
    private static final String KEY_GEMINI_MODEL = "gemini_model";
    private static final String KEY_TARGET_LANGUAGE = "target_language";
    private static final String KEY_SHOW_ORIGINAL = "show_original";
    private static final String KEY_SHOW_TRANSLATION = "show_translation";
    private static final String KEY_SHOW_PRONUNCIATION = "show_pronunciation";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";

    private final SharedPreferences prefs;
    private final SecureVault vault;

    public AppSettings(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        vault = new SecureVault(context);
        String legacy = prefs.getString(KEY_GEMINI_API_KEYS, prefs.getString("gemini_api_key", ""));
        if (legacy != null && !legacy.isEmpty()) {
            try {
                vault.put("gemini", legacy);
                prefs.edit().remove(KEY_GEMINI_API_KEYS).remove("gemini_api_key").apply();
            } catch (IllegalStateException ignored) { /* Show missing-key guidance if migration is unavailable. */ }
        }
    }

    public void saveUiSettings(
            String apiKeysRaw,
            String modelId,
            String targetLanguageCode,
            boolean showOriginal,
            boolean showTranslation,
            boolean showPronunciation
    ) {
        vault.put("gemini", clean(apiKeysRaw));
        prefs.edit()
                .putString(KEY_GEMINI_MODEL, clean(modelId))
                .putString(KEY_TARGET_LANGUAGE, clean(targetLanguageCode))
                .putBoolean(KEY_SHOW_ORIGINAL, showOriginal)
                .putBoolean(KEY_SHOW_TRANSLATION, showTranslation)
                .putBoolean(KEY_SHOW_PRONUNCIATION, showPronunciation)
                .apply();
    }

    public String getAiProvider() { return prefs.getString("ai_provider", "gemini"); }
    public static String providerLabel(String provider) { return "openai".equals(provider) ? "OpenAI (GPT)" : "Gemini"; }
    public String getApiKeys(String provider) { return vault.get("openai".equals(provider) ? "openai" : "gemini"); }
    public String getModel(String provider) {
        return "openai".equals(provider) ? prefs.getString("openai_model", "gpt-4.1-mini") : getGeminiModel();
    }
    public void saveProvider(String provider, String keys, String model) {
        vault.put(provider, clean(keys));
        prefs.edit().putString(provider + "_model", clean(model)).apply();
    }
    public void selectProvider(String provider) { prefs.edit().putString("ai_provider", provider).apply(); }

    public String getGeminiApiKeysRaw() {

        return vault.get("gemini");
    }

    public List<String> getGeminiApiKeys() {
        return parseApiKeys(getGeminiApiKeysRaw());
    }

    public String getPrimaryGeminiApiKey() {
        List<String> keys = getGeminiApiKeys();
        return keys.isEmpty() ? "" : keys.get(0);
    }

    public String getGeminiModel() {
        String value = prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL);
        return value == null || value.trim().isEmpty() ? DEFAULT_GEMINI_MODEL : value.trim();
    }

    public String getTargetLanguageCode() {
        String code = prefs.getString(KEY_TARGET_LANGUAGE, "ko");
        return code == null || code.trim().isEmpty() ? "ko" : code;
    }

    public String resolvedLanguage() {
        String code = getTargetLanguageCode();
        return "auto".equals(code) ? java.util.Locale.getDefault().getLanguage() : code;
    }
    public boolean isAiCleanupEnabled() { return prefs.getBoolean("ai_cleanup", false); }
    public void setAiCleanupEnabled(boolean value) { prefs.edit().putBoolean("ai_cleanup", value).apply(); }
    public String getAcrHost() { return prefs.getString("acr_host", ""); }
    public String getAcrKey() { return vault.get("acr_key"); }
    public String getAcrSecret() { return vault.get("acr_secret"); }
    public void saveAcr(String host, String key, String secret) {
        vault.put("acr_key", key.trim());
        vault.put("acr_secret", secret.trim());
        prefs.edit().putString("acr_host", host.trim()).apply();
    }

    public boolean isShowOriginalEnabled() {
        return prefs.getBoolean(KEY_SHOW_ORIGINAL, true);
    }

    public boolean isShowTranslationEnabled() {
        return prefs.getBoolean(KEY_SHOW_TRANSLATION, true);
    }

    public boolean isShowPronunciationEnabled() {
        return prefs.getBoolean(KEY_SHOW_PRONUNCIATION, false);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static List<String> parseApiKeys(String rawValue) {
        List<String> keys = new ArrayList<>();
        if (rawValue == null) {
            return keys;
        }

        String raw = rawValue.trim();
        if (raw.isEmpty()) {
            return keys;
        }

        if (raw.startsWith("[")) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    String value = array.optString(i, "").trim();
                    if (!value.isEmpty()) {
                        keys.add(value);
                    }
                }
                if (!keys.isEmpty()) {
                    return keys;
                }
            } catch (Exception ignored) {
                // Fall through to line-based parsing.
            }
        }

        String normalized = raw.replace(",", "\n");
        String[] parts = normalized.split("\\r?\\n");
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (!value.isEmpty()) {
                keys.add(value);
            }
        }
        return keys;
    }
}
