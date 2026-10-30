package com.example.lyrics_mobile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Reusable data operations. Only verified catalog records can become song details. */
public final class MusicRepository {
    private final MusicStore store;
    private final AppSettings settings;
    interface ClientFactory { LyricsAiClient create(OperationScope scope, String provider); }
    private final ClientFactory aiClients;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    public volatile boolean storageFailed;
    public MusicRepository(MusicStore store, AppSettings settings) { this(store, settings, LyricsAiClient::new); }
    MusicRepository(MusicStore store, AppSettings settings, ClientFactory aiClients) {
        this.store = store; this.settings = settings; this.aiClients = aiClients;
    }
    private Object lock(String key) {
        Object made = new Object(); Object old = locks.putIfAbsent(key, made); return old == null ? made : old;
    }
    public static class SearchCache { public List<SongInfo> songs; }
    public DataResult<List<SongInfo>> search(String query, OperationScope scope) {
        String key = "regional-v2:" + query.trim().toLowerCase(java.util.Locale.ROOT);
        synchronized (lock("search:" + key)) {
            SearchCache cached = store.read("search", key, SearchCache.class, MusicStore.WEEK);
            if (cached != null && cached.songs != null) return DataResult.ok(cached.songs);
            long token = store.generation();
            DataResult<List<SongInfo>> result = new SongSearchClient(scope).find(query);
            if (result.isSuccess() && !scope.isCancelled() && !result.value.isEmpty()) {
                SearchCache save = new SearchCache(); save.songs = result.value;
                storageFailed = !store.write("search", key, save, token);
            }
            return result;
        }
    }
    public volatile String searchNotice = "";
    public DataResult<List<SongInfo>> currentCandidates(SongInfo raw, OperationScope scope) {
        searchNotice = "";
        DataResult<List<SongInfo>> local = catalogCandidates(raw.getTitle(), raw.getArtist(), scope);
        if (!local.isSuccess() || !local.value.isEmpty() || scope.isCancelled() || !settings.isAiCleanupEnabled()) return local;
        String provider = settings.getAiProvider(), keys = settings.getApiKeys(provider), model = settings.getModel(provider);
        if (keys.isEmpty()) return local;
        String metaKey = raw.identity() + "::" + provider + "::" + model + "::metadata-v2";
        String cleaned = store.read("metadata", metaKey, String.class, MusicStore.MONTH);
        long token = store.generation();
        if (cleaned == null) {
            scope.progress("곡명과 아티스트 후보를 정리하고 있어요");
            LyricsAiClient.AiTextResult ai = new LyricsAiClient(scope, provider).cleanMetadata(raw.getTitle(), raw.getArtist(), keys, model);
            if (!ai.isSuccess()) { searchNotice = "AI 제목 정리: " + ai.getErrorMessage(); return local; }
            cleaned = ai.getText();
        }
        try {
            JsonObject candidate = JsonParser.parseString(cleaned).getAsJsonObject();
            String title = candidate.get("title").getAsString().trim(), artist = candidate.get("artist").getAsString().trim();
            if (title.isEmpty() || artist.isEmpty()) return local;
            if (!scope.isCancelled()) storageFailed = !store.write("metadata", metaKey, cleaned, token);
            return catalogCandidates(title, artist, scope);
        } catch (RuntimeException e) { searchNotice = "AI가 곡명·아티스트를 확실히 정리하지 못했어요. 직접 검색할 수 있어요."; return local; }
    }
    private DataResult<List<SongInfo>> catalogCandidates(String title, String artist, OperationScope scope) {
        String key = "current-v2:" + title + "\n" + artist;
        SearchCache cached = store.read("search", key, SearchCache.class, MusicStore.WEEK);
        if (cached != null && cached.songs != null && !cached.songs.isEmpty()) return DataResult.ok(cached.songs);
        long token = store.generation();
        DataResult<List<SongInfo>> result = new SongSearchClient(scope).findCandidates(title, artist);
        if (!result.isSuccess()) return result;
        List<SongInfo> originals = new ArrayList<>();
        for (SongInfo song : result.value) if (!TrackRules.alternateVersion(song.getTitle())) originals.add(song);
        if (!scope.isCancelled() && !originals.isEmpty()) {
            SearchCache cache = new SearchCache(); cache.songs = originals; storageFailed = !store.write("search", key, cache, token);
        }
        return DataResult.ok(originals);
    }
    public DataResult<LrcLibClient.Lyrics> lyrics(SongInfo song, OperationScope scope) {
        synchronized (lock("lyrics:" + song.identity())) {
            LrcLibClient.Lyrics cached = store.read("lyrics", "native-v2:" + song.identity(), LrcLibClient.Lyrics.class, MusicStore.MONTH);
            if (cached != null && cached.plain != null && !cached.plain.trim().isEmpty()) return DataResult.ok(cached);
            long token = store.generation();
            DataResult<LrcLibClient.Lyrics> result = new LrcLibClient(scope).find(song);
            if (result.isSuccess() && !scope.isCancelled()) storageFailed = !store.write("lyrics", "native-v2:" + song.identity(), result.value, token);
            return result;
        }
    }
    public LyricsAiClient.AiTextResult ai(SongInfo song, String lyrics, String lang, String provider, String model, String keys, String kind, OperationScope scope) {
        String backupKeys = settings.getApiKeys("gemini"), backupModel = settings.getModel("gemini");
        return AiFallback.run(provider, !AppSettings.parseApiKeys(backupKeys).isEmpty(), scope,
                selected -> aiOnce(song, lyrics, lang, selected, selected.equals(provider) ? model : backupModel,
                        selected.equals(provider) ? keys : backupKeys, kind, scope));
    }
    private LyricsAiClient.AiTextResult aiOnce(SongInfo song, String lyrics, String lang, String provider, String model, String keys, String kind, OperationScope scope) {
        String key = MusicStore.variant(song, lyrics, lang, "gemini".equals(provider) ? model : provider + ":" + model, kind);
        synchronized (lock(key)) {
            String cached = store.read("ai", key, String.class, MusicStore.MONTH);
            if (cached != null && !cached.trim().isEmpty()) return new LyricsAiClient.AiTextResult(true, cached, "");
            long token = store.generation();
            LyricsAiClient client = aiClients.create(scope, provider).withChunks(new LyricsAiClient.ChunkCache() {
                public String get(String chunkKey) { return store.read("ai_chunks", chunkKey, String.class, MusicStore.MONTH); }
                public void put(String chunkKey, String value) { storageFailed = !store.write("ai_chunks", chunkKey, value, token); }
            });
            long started = System.nanoTime();
            LyricsAiClient.AiTextResult result = "translation".equals(kind) ? client.translateLyrics(lyrics, lang, keys, model)
                    : client.createPronunciationGuide(lyrics, lang, keys, model);
            if (result.isSuccess() && !scope.isCancelled()) storageFailed = !store.write("ai", key, result.getText(), token);
            scope.record(provider, model, result.isSuccess() ? "SUCCESS" : result.getFailure().code, (System.nanoTime() - started) / 1000000);
            return result;
        }
    }
    public DataResult<MusicStore.Weekly> weekly(OperationScope scope) {
        synchronized (lock("weekly")) {
            MusicStore.Weekly previous = store.read("weekly", "batch", MusicStore.Weekly.class, 0);
            long now = System.currentTimeMillis();
            if (previous != null && previous.songs != null && !previous.songs.isEmpty() && now >= previous.generatedAt && now - previous.generatedAt < MusicStore.WEEK)
                return DataResult.ok(previous);
            Set<String> excluded = new HashSet<>();
            if (previous != null && previous.shown != null) excluded.addAll(previous.shown);
            List<String> seeds = new ArrayList<>();
            for (MusicStore.Visit visit : store.library().songs) {
                excluded.add(visit.song.identity());
                if (!visit.song.getArtist().isEmpty() && !seeds.contains(visit.song.getArtist()) && seeds.size() < 3) seeds.add(visit.song.getArtist());
            }
            if (seeds.isEmpty()) seeds.add("K-pop");
            MusicStore.Weekly batch = new MusicStore.Weekly(); batch.generatedAt = now;
            if (previous != null && previous.shown != null) batch.shown.addAll(previous.shown);
            long token = store.generation();
            for (String seed : seeds) {
                if (scope.isCancelled()) return DataResult.fail(AiFailure.exception(new java.io.InterruptedIOException()));
                DataResult<List<SongInfo>> candidates = search(seed, scope);
                if (!candidates.isSuccess()) return DataResult.fail(candidates.failure);
                for (SongInfo song : candidates.value) if (!TrackRules.alternateVersion(song.getTitle()) && excluded.add(song.identity())) {
                    batch.songs.add(song); batch.shown.add(song.identity());
                    if (batch.songs.size() == 10) break;
                }
                if (batch.songs.size() == 10) break;
            }
            if (batch.songs.isEmpty()) return DataResult.fail(DataResult.missing("새로운 추천 후보가 없어요. 다른 곡을 찾아보면 다음 추천에 반영돼요."));
            while (batch.shown.size() > 300) batch.shown.remove(0);
            if (!scope.isCancelled()) storageFailed = !store.write("weekly", "batch", batch, token);
            return DataResult.ok(batch);
        }
    }
}
