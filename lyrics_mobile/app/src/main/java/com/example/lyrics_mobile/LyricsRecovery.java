package com.example.lyrics_mobile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Recovery after an initial LRCLIB NOT_FOUND. AI supplies metadata, never lyrics or URLs. */
public final class LyricsRecovery {
    /** Extension point only. No secondary service integration or subscription is bundled. */
    public interface OfficialProvider {
        boolean isAvailable();
        String sourceName();
        /** Implementations must verify identity and return licensed/source lyrics, not generated text. */
        DataResult<LrcLibClient.Lyrics> find(SongInfo song, OperationScope scope);
    }
    public static final OfficialProvider UNAVAILABLE = new OfficialProvider() {
        public boolean isAvailable() { return false; }
        public String sourceName() { return "보조 공식 가사 제공처"; }
        public DataResult<LrcLibClient.Lyrics> find(SongInfo song, OperationScope scope) {
            return DataResult.fail(new AiFailure("PROVIDER_UNAVAILABLE", "제공처 미연결",
                    "보조 공식 가사 제공처가 연결되지 않아 조회하지 않았어요.", AiFailure.Action.RETRY));
        }
    };
    interface Catalog { DataResult<List<SongInfo>> find(String title, String artist); }
    interface Fetch { DataResult<LrcLibClient.Lyrics> find(SongInfo song); }
    interface Metadata { LyricsAiClient.AiTextResult clean(SongInfo song); }
    private final Catalog catalog;
    private final Fetch fetch;
    private final Metadata metadata;
    private final OfficialProvider secondary;

    LyricsRecovery(Catalog catalog, Fetch fetch, Metadata metadata, OfficialProvider secondary) {
        this.catalog = catalog; this.fetch = fetch; this.metadata = metadata;
        this.secondary = secondary == null ? UNAVAILABLE : secondary;
    }

    public DataResult<LrcLibClient.Lyrics> recover(SongInfo song, OperationScope scope, boolean allowAi) {
        if (cancelled(scope)) return cancelledResult();
        String title = TrackRules.cleanTitle(song.getTitle());
        String artist = TrackRules.cleanArtist(song.getArtist());
        // Presentation cleanup only: never turn live/cover/remix metadata into a studio recording.
        if (!title.equals(song.getTitle()) || !artist.equals(song.getArtist())) {
            DataResult<LrcLibClient.Lyrics> local = verified(song, title, artist, scope, "곡 정보 정리 후 조회");
            if (!missing(local)) return local;
        }
        if (cancelled(scope)) return cancelledResult();
        boolean available = secondary.isAvailable();
        scope.record("secondary-lyrics", "", available ? "AVAILABLE" : "PROVIDER_UNAVAILABLE", 0);
        if (available) {
            DataResult<LrcLibClient.Lyrics> result = secondary.find(song, scope);
            if (cancelled(scope)) return cancelledResult();
            if (result.isSuccess()) return sourced(result, secondary.sourceName(), "공식 제공처 조회");
            if (!missing(result)) return result;
        }
        if (allowAi) {
            if (cancelled(scope)) return cancelledResult();
            if (!safe(title) || !safe(artist) || song.getDurationMs() <= 0 || song.getDurationMs() > 3600000
                    || TrackRules.alternateVersion(song.getTitle()))
                return notFound(available, "같은 곡인지 검증할 정보가 부족해 유료 AI 요청을 보내지 않았어요.");
            scope.progress("AI로 곡명과 아티스트 정보만 정리하고 있어요");
            LyricsAiClient.AiTextResult ai = metadata.clean(song);
            if (cancelled(scope)) return cancelledResult();
            if (!ai.isSuccess()) return DataResult.fail(ai.getFailure());
            String proposedTitle, proposedArtist;
            try {
                JsonObject object = JsonParser.parseString(ai.getText()).getAsJsonObject();
                proposedTitle = field(object, "title"); proposedArtist = field(object, "artist");
            } catch (RuntimeException invalid) { return notFound(available, "AI가 곡 정보를 확실히 정리하지 못했어요."); }
            if (!proposedTitle.equals(title) || !proposedArtist.equals(artist)) {
                DataResult<LrcLibClient.Lyrics> result = verified(song, proposedTitle, proposedArtist, scope, "AI 곡 정보 정리 및 음악 목록 검증 후 조회");
                if (!missing(result)) return result;
            }
        }
        return notFound(available, "곡 정보와 확실히 일치하는 출처의 가사를 찾지 못했어요.");
    }

    private DataResult<LrcLibClient.Lyrics> verified(SongInfo original, String title, String artist,
            OperationScope scope, String route) {
        if (cancelled(scope)) return cancelledResult();
        if (!safe(title) || !safe(artist) || !sameArtist(original.getArtist(), artist)
                || TrackRules.alternateVersion(original.getTitle()) || TrackRules.alternateVersion(title)
                || original.getDurationMs() <= 0 || original.getDurationMs() > 3600000)
            return DataResult.fail(DataResult.missing("곡 정보를 안전하게 확인할 수 없어요."));
        scope.progress("음악 목록에서 곡명·아티스트·재생 길이를 확인하고 있어요");
        DataResult<List<SongInfo>> found = catalog.find(title, artist);
        if (cancelled(scope)) return cancelledResult();
        if (!found.isSuccess()) return DataResult.fail(found.failure);
        SongInfo selected = null;
        for (SongInfo candidate : found.value) {
            if (!identityMatches(original, title, candidate)) continue;
            // Only identical catalog records may be deduplicated; distinct editions remain ambiguous.
            if (selected != null && (!selected.getCatalogId().equals(candidate.getCatalogId())
                    || !selected.getTitle().equals(candidate.getTitle())
                    || !selected.getArtist().equals(candidate.getArtist())
                    || !selected.getAlbum().equals(candidate.getAlbum())
                    || selected.getDurationMs() != candidate.getDurationMs()))
                return DataResult.fail(DataResult.missing("일치하는 곡 후보가 여러 개라 가사를 가져오지 않았어요."));
            selected = candidate;
        }
        if (selected == null) return DataResult.fail(DataResult.missing("음악 목록에서 일치하는 곡을 확인하지 못했어요."));
        scope.progress("확인된 곡 정보로 LRCLIB의 실제 가사를 조회하고 있어요");
        DataResult<LrcLibClient.Lyrics> result = fetch.find(selected);
        if (cancelled(scope)) return cancelledResult();
        return result.isSuccess() ? sourced(result, "LRCLIB", route) : result;
    }
    static boolean identityMatches(SongInfo original, String proposedTitle, SongInfo candidate) {
        return !candidate.getCatalogId().isEmpty() && safe(proposedTitle)
                && key(proposedTitle).equals(key(candidate.getTitle()))
                && sameArtist(original.getArtist(), candidate.getArtist())
                && !TrackRules.alternateVersion(original.getTitle()) && !TrackRules.alternateVersion(candidate.getTitle())
                && original.getDurationMs() > 0 && original.getDurationMs() <= 3600000
                && candidate.getDurationMs() > 0 && candidate.getDurationMs() <= 3600000
                && Math.abs(original.getDurationMs() - candidate.getDurationMs()) <= 2000;
    }
    private static String key(String text) {
        return Normalizer.normalize(text.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
    private static boolean sameArtist(String left, String right) {
        String expected = key(TrackRules.cleanArtist(left));
        return !expected.isEmpty() && expected.equals(key(TrackRules.cleanArtist(right)));
    }
    private static boolean safe(String value) {
        return value != null && !value.trim().isEmpty() && value.length() <= 300
                && !value.contains("://") && !value.contains("\n") && !value.contains("\r");
    }
    private static String field(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
        return value.getAsString().trim();
    }
    private static boolean missing(DataResult<?> result) { return !result.isSuccess() && "NOT_FOUND".equals(result.failure.code); }
    private static boolean cancelled(OperationScope scope) { return scope.isCancelled() || Thread.currentThread().isInterrupted(); }
    private static DataResult<LrcLibClient.Lyrics> cancelledResult() {
        return DataResult.fail(AiFailure.exception(new java.io.InterruptedIOException()));
    }
    private static DataResult<LrcLibClient.Lyrics> notFound(boolean available, String reason) {
        return DataResult.fail(DataResult.missing(reason + (available ? "" :
                " 보조 공식 가사 제공처가 연결되지 않아 조회하지 않았어요.")));
    }
    private static DataResult<LrcLibClient.Lyrics> sourced(DataResult<LrcLibClient.Lyrics> result, String source, String route) {
        if (result.value == null || result.value.plain == null || result.value.plain.trim().isEmpty())
            return DataResult.fail(AiFailure.response("EMPTY"));
        LrcLibClient.Lyrics lyrics = new LrcLibClient.Lyrics();
        lyrics.plain = result.value.plain; lyrics.synced = result.value.synced;
        lyrics.source = source;
        lyrics.notice = "가사 출처: " + source + " (" + route + "). " + (result.value.notice == null ? "" : result.value.notice);
        return DataResult.ok(lyrics);
    }

    static String catalogCountry(String title) {
        if (LyricsLanguage.hasJapanese(title) || LyricsLanguage.hasHan(title)) return "JP";
        return LyricsLanguage.hasKorean(title) ? "KR" : "US";
    }

    /** A single catalog request: no cross-store retries or model-supplied URLs. */
    static DataResult<List<SongInfo>> catalog(String title, String artist, OperationScope scope) {
        OkHttpClient client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build();
        String country = catalogCountry(title);
        HttpUrl url = HttpUrl.get("https://itunes.apple.com/search").newBuilder()
                .addQueryParameter("term", title + " " + artist).addQueryParameter("entity", "song")
                .addQueryParameter("country", country).addQueryParameter("lang", country.equals("JP") ? "ja_jp" : "en_us")
                .addQueryParameter("limit", "200").build();
        try (Response response = scope.execute(client.newCall(new Request.Builder().url(url).build()))) {
            if (!response.isSuccessful()) return DataResult.fail(DataResult.service(response.code(), "음악 목록 서비스"));
            if (response.body() == null) return DataResult.fail(AiFailure.response("EMPTY"));
            List<SongInfo> songs = new ArrayList<>();
            for (JsonElement element : JsonParser.parseString(response.body().string()).getAsJsonObject().getAsJsonArray("results")) {
                JsonObject item = element.getAsJsonObject();
                if (!item.has("trackId") || !item.has("trackTimeMillis")) continue;
                songs.add(new SongInfo(field(item, "trackName"), field(item, "artistName"),
                        item.has("collectionName") ? field(item, "collectionName") : "",
                        item.get("trackId").getAsString(), "", item.get("trackTimeMillis").getAsLong()));
            }
            return DataResult.ok(songs);
        } catch (Exception e) { return DataResult.fail(AiFailure.exception(e)); }
    }
}
