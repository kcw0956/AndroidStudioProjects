package com.example.lyrics_mobile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Public work aliases expand query spellings; they never establish a song's artist. */
final class NativeTitleAliases {
    private static long nextRequest;
    private static synchronized boolean reserve() {
        long now = System.nanoTime();
        if (now < nextRequest) return false;
        nextRequest = now + TimeUnit.MILLISECONDS.toNanos(1100); return true;
    }
    static String token(String query) {
        if (query == null || !query.matches("[A-Za-z0-9 '\"-]+")) return "";
        String[] words = query.trim().split("\\s+");
        String word = words[words.length - 1].replaceAll("[^A-Za-z]", "");
        return word.length() >= 4 ? word : "";
    }
    static List<String> parse(String body, String token) {
        List<String> titles = new ArrayList<>();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("works") || !root.get("works").isJsonArray()) return titles;
        for (JsonElement element : root.getAsJsonArray("works")) {
            JsonObject work = element.getAsJsonObject();
            if (!work.has("title") || !work.has("aliases") || !work.get("aliases").isJsonArray()) continue;
            String nativeTitle = work.get("title").getAsString();
            if (!LyricsLanguage.hasJapanese(nativeTitle) && !LyricsLanguage.hasHan(nativeTitle) && !LyricsLanguage.hasKorean(nativeTitle)) continue;
            for (JsonElement alias : work.getAsJsonArray("aliases")) {
                JsonObject item = alias.getAsJsonObject();
                if (item.has("name") && TrackRules.normalized(item.get("name").getAsString()).equals(TrackRules.normalized(token))) {
                    if (!titles.contains(nativeTitle)) titles.add(nativeTitle); break;
                }
            }
            if (titles.size() == 2) break;
        }
        return titles;
    }
    static List<String> find(String token, OperationScope scope) throws Exception {
        if (token.isEmpty() || !reserve()) return new ArrayList<>();
        HttpUrl url = HttpUrl.get("https://musicbrainz.org/ws/2/work/").newBuilder()
                .addQueryParameter("query", token).addQueryParameter("fmt", "json").addQueryParameter("limit", "30").build();
        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build();
        try (Response response = scope.execute(client.newCall(new Request.Builder().url(url)
                .header("User-Agent", "LyricsMobile/2.0 (Android; private development)").build()))) {
            if (!response.isSuccessful() || response.body() == null) throw new java.io.IOException("Alias lookup unavailable");
            return parse(response.body().string(), token);
        }
    }
}
