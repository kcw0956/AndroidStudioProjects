package com.example.lyrics_mobile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LrcLibClient {
    public static class Lyrics { public String plain = ""; public String synced = ""; public String notice = ""; public String source = "LRCLIB"; public boolean aiSupplied; }
    private final OperationScope scope;
    private static final OkHttpClient DEFAULT_CLIENT = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build();
    private final OkHttpClient client;
    public LrcLibClient() { this(new OperationScope()); }
    public LrcLibClient(OperationScope scope) { this(scope, DEFAULT_CLIENT); }
    LrcLibClient(OperationScope scope, OkHttpClient client) { this.scope = scope; this.client = client; }
    public DataResult<Lyrics> find(SongInfo song) {
        scope.progress("곡 정보와 일치하는 가사를 찾고 있어요");
        HttpUrl.Builder url = HttpUrl.get("https://lrclib.net/api/get").newBuilder()
                .addQueryParameter("artist_name", song.getArtist()).addQueryParameter("track_name", song.getTitle());
        if (!song.getAlbum().isEmpty()) url.addQueryParameter("album_name", song.getAlbum());
        if (song.getDurationMs() > 0 && song.getDurationMs() <= 3600000) url.addQueryParameter("duration", String.valueOf(Math.round(song.getDurationMs() / 1000.0)));
        DataResult<JsonElement> direct = request(url.build());
        Lyrics initial = null;
        if (direct.isSuccess() && direct.value.isJsonObject()) {
            JsonObject record = direct.value.getAsJsonObject();
            if (matches(record, song, false)) initial = parse(record);
        }
        if (scope.isCancelled()) return DataResult.fail(AiFailure.exception(new java.io.InterruptedIOException()));
        if (initial != null && !initial.plain.isEmpty() && !LyricsLanguage.isLatinOnly(initial.plain)) return DataResult.ok(initial);
        // Never retry overload/rate-limit responses in the same operation.
        if (!direct.isSuccess() && !"NOT_FOUND".equals(direct.failure.code)) return DataResult.fail(direct.failure);
        scope.progress("다른 가사 등록본의 원문 표기를 확인하고 있어요");
        DataResult<JsonElement> alternatives = request(HttpUrl.get("https://lrclib.net/api/search").newBuilder()
                .addQueryParameter("track_name", song.getTitle()).addQueryParameter("artist_name", song.getArtist()).build());
        if (scope.isCancelled() || Thread.currentThread().isInterrupted()) return DataResult.fail(AiFailure.exception(new java.io.InterruptedIOException()));
        Lyrics selected = initial;
        if (alternatives.isSuccess() && alternatives.value.isJsonArray()) {
            for (JsonElement item : alternatives.value.getAsJsonArray()) {
                if (!item.isJsonObject() || !matches(item.getAsJsonObject(), song, true)) continue;
                Lyrics candidate = parse(item.getAsJsonObject());
                if (candidate.plain.isEmpty()) continue;
                if (selected == null || selected.plain.isEmpty() || preferOriginalScript(candidate, selected, song)) selected = candidate;
            }
        }
        if (selected == null || selected.plain.isEmpty()) return alternatives.isSuccess()
                ? DataResult.fail(DataResult.missing("곡명·가수·재생 길이가 일치하는 가사를 찾지 못했어요.")) : DataResult.fail(alternatives.failure);
        if (LyricsLanguage.isLatinOnly(selected.plain)) selected.notice = "제공된 가사는 라틴 문자예요. 영어인지 로마자 발음인지 확인되지 않았으며 원문 표기를 찾지 못했어요.";
        else if (selected != initial) selected.notice = "곡명·가수·재생 길이가 일치하는 다른 등록본을 사용했어요. 원문 언어는 문자 기준으로 표시해요.";
        if (!alternatives.isSuccess()) selected.notice += " 다른 가사 등록본 조회에 실패했어요. 현재 확보된 가사를 표시해요.";
        return DataResult.ok(selected);
    }
    static boolean preferOriginalScript(Lyrics candidate, Lyrics selected, SongInfo song) {
        if (!LyricsLanguage.isLatinOnly(selected.plain)) return false;
        String metadata = song.getTitle() + " " + song.getAlbum();
        if (LyricsLanguage.hasKorean(metadata)) return LyricsLanguage.hasKorean(candidate.plain);
        if (LyricsLanguage.hasJapanese(metadata) || LyricsLanguage.hasHan(metadata)) return LyricsLanguage.hasJapanese(candidate.plain);
        // Latin titles alone do not establish the song's original language.
        return false;
    }
    static boolean matches(JsonObject record, SongInfo song, boolean alternative) {
        if (!TrackRules.normalized(text(record, "trackName")).equals(TrackRules.normalized(song.getTitle()))
                || !TrackRules.normalized(text(record, "artistName")).equals(TrackRules.normalized(song.getArtist()))) return false;
        double duration = 0;
        try { if (record.has("duration") && !record.get("duration").isJsonNull()) duration = record.get("duration").getAsDouble(); }
        catch (RuntimeException ignored) { return false; }
        if (alternative && (duration <= 0 || song.getDurationMs() <= 0)) return false;
        return song.getDurationMs() <= 0 || (duration > 0 && Math.abs(duration - song.getDurationMs() / 1000.0) <= 2.0);
    }
    static Lyrics parse(JsonObject record) {
        Lyrics lyrics = new Lyrics();
        lyrics.plain = text(record, "plainLyrics").trim();
        lyrics.synced = text(record, "syncedLyrics").trim();
        if (lyrics.plain.isEmpty()) lyrics.plain = stripTimestamps(lyrics.synced);
        // Some records have romanized plain lyrics but original-script synchronized lyrics.
        String stripped = stripTimestamps(lyrics.synced);
        if (LyricsLanguage.isLatinOnly(lyrics.plain) && (LyricsLanguage.hasJapanese(stripped) || LyricsLanguage.hasKorean(stripped))) lyrics.plain = stripped;
        return lyrics;
    }
    private DataResult<JsonElement> request(HttpUrl url) {
        long started = System.nanoTime();
        try (Response response = scope.execute(client.newCall(new Request.Builder().url(url).header("User-Agent", "LyricsMobile/2.0").build()))) {
            scope.record("LRCLIB", url.encodedPath().endsWith("search") ? "search" : "get", "HTTP_" + response.code(), (System.nanoTime() - started) / 1000000);
            if (response.code() == 404) return DataResult.fail(DataResult.missing("이 곡에 등록된 가사가 없어요."));
            if (!response.isSuccessful()) {
                AiFailure failure = DataResult.service(response.code(), "LRCLIB 가사 서버");
                String retry = response.header("Retry-After");
                if (retry != null && retry.matches("[0-9]{1,6}")) failure = new AiFailure(failure.code, failure.title,
                        failure.message + "\n\n서버가 안내한 최소 대기 시간: " + Integer.parseInt(retry) + "초. 이후에도 복구가 보장되지는 않아요.", failure.action);
                return DataResult.fail(failure);
            }
            if (response.body() == null) return DataResult.fail(AiFailure.response("EMPTY"));
            return DataResult.ok(JsonParser.parseString(response.body().string()));
        } catch (Exception e) {
            AiFailure failure = AiFailure.exception(e);
            scope.record("LRCLIB", "request", failure.code, (System.nanoTime() - started) / 1000000);
            return DataResult.fail(failure);
        }
    }
    private static String text(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsString() : "";
    }
    public static String stripTimestamps(String text) {
        return text == null ? "" : text.replaceAll("(?m)^\\s*(?:\\[\\d+:\\d+(?:[.:]\\d+)?\\])+", "")
                .replaceAll("(?m)^\\[(?:ar|ti|al|by|offset|length):[^\\]]*\\]\\s*", "").trim();
    }
}
