package com.example.lyrics_mobile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SongSearchClient {
    private final OperationScope scope;
    private static final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build();
    private String notice = "";
    public SongSearchClient() { this(new OperationScope()); }
    public SongSearchClient(OperationScope scope) { this.scope = scope; }
    /** Read immediately after find; a nonempty notice means results may be incomplete. */
    public String getNotice() { return notice; }
    public DataResult<List<SongInfo>> find(String keyword) { return findCandidates(keyword, ""); }
    public DataResult<List<SongInfo>> findCandidates(String title, String artist) {
        notice = "";
        List<String> queries = TrackRules.searchQueries(title, artist);
        List<SongInfo> found = new ArrayList<>();
        AiFailure failure = null;
        int successes = 0, failures = 0;
        List<String> nativeTitles = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(24);
        // All storefronts are consulted, even when an earlier storefront has unrelated hits.
        for (int q = 0; q < Math.min(2, queries.size()); q++) {
            for (String country : new String[]{"JP", "KR", "US"}) {
                if (scope.isCancelled() || Thread.currentThread().isInterrupted()) return DataResult.fail(AiFailure.exception(new java.io.InterruptedIOException()));
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) { failures++; continue; }
                scope.progress((country.equals("JP") ? "일본" : country.equals("KR") ? "한국" : "미국") + " 음악 목록에서 곡을 찾고 있어요");
                DataResult<List<SongInfo>> result = findInStore(queries.get(q), country, Math.min(6000, left));
                if (result.isSuccess()) { successes++; found.addAll(result.value); }
                else { failures++; failure = result.failure; }
            }
            if (!found.isEmpty()) break;
        }
        if (scope.isCancelled()) return DataResult.fail(AiFailure.exception(new java.io.InterruptedIOException()));
        String aliasToken = NativeTitleAliases.token(TrackRules.originalQuery(title));
        if (!aliasToken.isEmpty() && deadline - System.nanoTime() > TimeUnit.SECONDS.toNanos(5)) {
            scope.progress("곡명의 다른 표기와 원제를 확인하고 있어요");
            try {
                nativeTitles = NativeTitleAliases.find(aliasToken, scope);
                for (String nativeTitle : nativeTitles) {
                    long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (left <= 0 || scope.isCancelled()) break;
                    String expanded = TrackRules.originalQuery(title).replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(aliasToken) + "\\b", java.util.regex.Matcher.quoteReplacement(nativeTitle));
                    DataResult<List<SongInfo>> alternate = findInStore(TrackRules.originalQuery(expanded, artist), "JP", Math.min(6000, left));
                    if (alternate.isSuccess()) { found.addAll(0, alternate.value); successes++; }
                    else { failures++; failure = alternate.failure; }
                }
            } catch (Exception e) { notice = "원제 별칭 조회에 연결하지 못해 로마자 검색 후보가 빠질 수 있어요."; }
        }
        if (scope.isCancelled()) return DataResult.fail(AiFailure.exception(new java.io.InterruptedIOException()));
        if (failures > 0) notice = "일부 국가의 검색에 연결하지 못해 결과가 빠질 수 있어요. 잠시 후 다시 검색해 주세요.";
        if ((successes == 0 || found.isEmpty()) && failures > 0) return DataResult.fail(failure == null
                ? AiFailure.exception(new java.net.SocketTimeoutException()) : failure);
        scope.progress("검색 결과의 곡명과 가수를 비교하고 있어요");
        List<SongInfo> ranked = mergeAndRank(found, title, artist);
        java.util.Set<String> nativeNames = new java.util.HashSet<>();
        for (String name : nativeTitles) nativeNames.add(TrackRules.normalized(name));
        Collections.sort(ranked, (a,b) -> Integer.compare(score(b,title,artist) + (nativeNames.contains(TrackRules.normalized(b.getTitle())) ? 100 : 0),
                score(a,title,artist) + (nativeNames.contains(TrackRules.normalized(a.getTitle())) ? 100 : 0)));
        return DataResult.ok(ranked);
    }
    static List<SongInfo> mergeAndRank(List<SongInfo> input, String title, String artist) {
        List<SongInfo> result = new ArrayList<>();
        for (SongInfo song : input) {
            boolean duplicate = false;
            for (SongInfo existing : result) {
                if ((!song.getCatalogId().isEmpty() && song.getCatalogId().equals(existing.getCatalogId()))
                        || (TrackRules.normalized(song.getTitle()).equals(TrackRules.normalized(existing.getTitle()))
                        && TrackRules.normalized(song.getArtist()).equals(TrackRules.normalized(existing.getArtist()))
                        && song.getDurationMs() > 0 && existing.getDurationMs() > 0
                        && Math.abs(song.getDurationMs() - existing.getDurationMs()) <= 2000)) { duplicate = true; break; }
            }
            if (!duplicate) result.add(song);
        }
        Collections.sort(result, (a, b) -> Integer.compare(score(b, title, artist), score(a, title, artist)));
        return result;
    }
    static int score(SongInfo song, String title, String artist) {
        String query = TrackRules.normalized(TrackRules.originalQuery(title));
        String track = TrackRules.normalized(song.getTitle());
        String expected = TrackRules.normalized(TrackRules.cleanArtist(artist));
        String actual = TrackRules.normalized(song.getArtist());
        int score = query.equals(track) ? 100 : (!track.isEmpty() && query.contains(track) ? 65 : 0);
        if (!expected.isEmpty()) score += expected.equals(actual) ? 150 : -100;
        else if (!actual.isEmpty() && query.contains(actual)) score += 80;
        if (TrackRules.alternateVersion(song.getTitle()) && !TrackRules.alternateVersion(title)) score -= 20;
        return score;
    }
    private DataResult<List<SongInfo>> findInStore(String keyword, String country, long timeoutMs) {
        try {
            HttpUrl url = HttpUrl.get("https://itunes.apple.com/search").newBuilder()
                    .addQueryParameter("term", keyword).addQueryParameter("entity", "song")
                    .addQueryParameter("country", country).addQueryParameter("lang", country.equals("JP") ? "ja_jp" : "en_us")
                    .addQueryParameter("limit", "50").build();
            okhttp3.Call call = client.newCall(new Request.Builder().url(url).build());
            call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS);
            try (Response response = scope.execute(call)) {
                if (!response.isSuccessful()) return DataResult.fail(DataResult.service(response.code(), "곡 검색 서비스"));
                if (response.body() == null) return DataResult.fail(AiFailure.response("EMPTY"));
                JsonArray array = JsonParser.parseString(response.body().string()).getAsJsonObject().getAsJsonArray("results");
                if (array == null) return DataResult.fail(AiFailure.response("INVALID"));
                List<SongInfo> songs = new ArrayList<>();
                for (int i = 0; i < array.size(); i++) {
                    JsonObject item = array.get(i).getAsJsonObject();
                    SongInfo song = new SongInfo(text(item, "trackName"), text(item, "artistName"), text(item, "collectionName"),
                            text(item, "trackId"), text(item, "trackViewUrl"), item.has("trackTimeMillis") && !item.get("trackTimeMillis").isJsonNull() ? item.get("trackTimeMillis").getAsLong() : 0);
                    song.withArtworkUrl(text(item, "artworkUrl100"));
                    if (song.isValid() && !song.getArtist().isEmpty()) songs.add(song);
                }
                return DataResult.ok(songs);
            }
        } catch (Exception e) { return DataResult.fail(AiFailure.exception(e)); }
    }
    private String text(JsonObject obj, String key) { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : ""; }
}
