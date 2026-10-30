package com.example.lyrics_mobile;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retains results and active operations across Activity recreation. */
public final class MusicViewModel extends AndroidViewModel {
    public final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    public final AppSettings settings;
    public final MusicStore store;
    private final MusicRepository repository;
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, OperationScope> operations = new HashMap<>();
    private int revision;
    public String page = "home", query = "", searchMessage = "", detailTab = "original", source = "";
    public List<SongInfo> results = new ArrayList<>();
    public boolean searching, loadingLyrics, loadingAi, loadingWeekly, workingSettings;
    public boolean savedOnly;
    public SongInfo selected;
    public LrcLibClient.Lyrics lyrics;
    public MusicStore.Weekly weekly;
    public AiFailure failure;
    public String failureOperation = "";
    public final Map<String, String> outputs = new HashMap<>();
    public final Map<String, String> outputSources = new HashMap<>();
    public final Map<String, String> outputErrors = new HashMap<>();
    private final java.util.LinkedHashSet<String> pendingOutputs = new java.util.LinkedHashSet<>();
    public String aiStatus = "";
    private String failedOutput = "translation";

    public String settingsStatus = "", storageNotice = "";
    public Draft draft;
    public boolean recognizing;
    public String recognitionStatus = "";
    public static class Draft {
        public String keys, model, provider, language, acrHost, acrKey, acrSecret;
        public final Map<String, String> providerKeys = new HashMap<>(), providerModels = new HashMap<>();
        public boolean original, translation, pronunciation, cleanup;
    }
    public MusicViewModel(@NonNull Application app) {
        super(app); settings = new AppSettings(app); store = new MusicStore(app); repository = new MusicRepository(store, settings);
    }
    private OperationScope start(String name) {
        OperationScope old = operations.remove(name); if (old != null) old.cancel();
        OperationScope scope = new OperationScope(); operations.put(name, scope); return scope;
    }
    public void notifyUi() { changes.setValue(++revision); }
    private void done(OperationScope scope, Runnable update) {
        main.post(() -> { if (!scope.isCancelled()) { update.run(); notifyUi(); } });
    }
    private void error(AiFailure value, String operation) { failure = value; failureOperation = operation; }
    public void navigate(String target) {
        if ("settings".equals(page) && !"settings".equals(target) && !applyAiDraft()) { notifyUi(); return; }
        page = target;
        if ("settings".equals(target) && draft == null) loadDraft();
        notifyUi();
        if ("weekly".equals(target) && !loadingWeekly) loadWeekly();
    }
    public void loadDraft() {
        Draft d = new Draft();
        d.provider = settings.getAiProvider();
        for (String provider : new String[]{"gemini", "openai"}) {
            d.providerKeys.put(provider, settings.getApiKeys(provider)); d.providerModels.put(provider, settings.getModel(provider));
        }
        d.keys = d.providerKeys.get(d.provider); d.model = d.providerModels.get(d.provider); d.language = settings.getTargetLanguageCode();
        d.original = settings.isShowOriginalEnabled(); d.translation = settings.isShowTranslationEnabled();
        d.pronunciation = settings.isShowPronunciationEnabled(); d.cleanup = settings.isAiCleanupEnabled();
        d.acrHost = settings.getAcrHost(); d.acrKey = settings.getAcrKey(); d.acrSecret = settings.getAcrSecret(); draft = d;
    }
    public void switchProvider(String provider) {
        if ((!"gemini".equals(provider) && !"openai".equals(provider)) || draft == null || provider.equals(draft.provider)) return;
        OperationScope scope = operations.remove("settings"); if (scope != null) scope.cancel();
        workingSettings = false;
        draft.providerKeys.put(draft.provider, draft.keys); draft.providerModels.put(draft.provider, draft.model);
        try {
            settings.saveProvider(draft.provider, draft.keys, draft.model.trim());
            settings.selectProvider(provider);
        } catch (IllegalStateException e) { settingsStatus = e.getMessage(); notifyUi(); return; }
        draft.provider = provider; draft.keys = draft.providerKeys.get(provider); draft.model = draft.providerModels.get(provider);
        invalidateAi();
        workingSettings = false; settingsStatus = ""; models.clear(); notifyUi();
    }
    private void invalidateAi() {
        OperationScope ai = operations.remove("ai"); if (ai != null) ai.cancel();
        loadingAi = false; outputs.remove("translation"); outputs.remove("pronunciation"); outputSources.clear(); outputErrors.clear(); pendingOutputs.clear(); aiStatus = "";
    }
    private boolean applyAiDraft() {
        if (draft == null) return true;
        try {
            if (!draft.model.trim().matches("openai".equals(draft.provider) ? "[A-Za-z0-9][A-Za-z0-9._:-]*" : "gemini-[A-Za-z0-9._-]+")) {
                settingsStatus = "모델 ID를 확인해 주세요."; return false;
            }
            settings.saveProvider(draft.provider, draft.keys, draft.model.trim());
            settings.selectProvider(draft.provider); invalidateAi(); return true;
        } catch (IllegalStateException e) { settingsStatus = e.getMessage(); return false; }
    }
    public void search(boolean originalsOnly) {

        if (query.trim().isEmpty()) { error(DataResult.missing("제목이나 아티스트를 입력해 주세요."), "search"); notifyUi(); return; }
        OperationScope scope = start("search"); searching = true; results = new ArrayList<>(); searchMessage = "검색 중이에요";
        scope.onProgress(message -> done(scope, () -> searchMessage = message));
        String term = query; notifyUi();
        workers.execute(() -> {
            DataResult<List<SongInfo>> found = repository.search(term, scope);
            done(scope, () -> {
                searching = false;
                if (!found.isSuccess()) { error(found.failure, "search"); searchMessage = found.failure.message; return; }
                results = new ArrayList<>();
                for (SongInfo song : found.value) if (!originalsOnly || !TrackRules.alternateVersion(song.getTitle())) results.add(song);
                searchMessage = results.isEmpty() ? "찾지 못했어요. 제목이나 가수를 바꿔 검색해 주세요."
                        : originalsOnly ? "원곡 후보예요. 제목과 가수를 확인하고 선택해 주세요." : results.size() + "곡을 찾았어요";
                storageWarning();
            });
        });
    }
    public void cancelSearch() {
        OperationScope scope = operations.remove("search"); if (scope != null) scope.cancel();
        searching = false; searchMessage = "검색을 취소했어요"; notifyUi();
    }
    public void current(SongInfo raw) {
        if (raw == null) { error(DataResult.missing("재생 정보를 받지 못했어요. 음악을 재생하고 알림 접근 권한을 확인해 주세요."), "current"); notifyUi(); return; }
        page = "home"; query = TrackRules.cleanTitle(raw.getTitle());
        OperationScope scope = start("search"); searching = true; results = new ArrayList<>(); searchMessage = "재생 정보로 곡 후보를 찾고 있어요"; notifyUi();
        scope.onProgress(message -> done(scope, () -> searchMessage = message));
        workers.execute(() -> {
            DataResult<List<SongInfo>> found = repository.currentCandidates(raw, scope);
            done(scope, () -> {
                searching = false;
                if (!found.isSuccess()) { searchMessage = found.failure.message; error(found.failure, "current"); }
                else { results = found.value; searchMessage = results.isEmpty() ? "확인할 수 있는 원곡을 찾지 못했어요. 직접 검색해 주세요." : "현재곡의 원곡 후보예요. 제목과 가수를 확인해 주세요."; }
                storageWarning();
            });
        });
    }
    public void open(SongInfo song, String from) {
        selected = song; source = from; lyrics = null; outputs.clear(); outputSources.clear(); outputErrors.clear(); pendingOutputs.clear(); failure = null;
        detailTab = settings.isShowOriginalEnabled() ? "original" : settings.isShowTranslationEnabled() ? "translation" : "pronunciation";
        loadingAi = false; loadingLyrics = true;
        OperationScope scope = start("detail");
        start("ai").cancel();
        notifyUi();
        workers.execute(() -> {
            long token = store.generation();
            if (!scope.isCancelled() && !store.visit(song, from, token)) storageNotice = "기록을 저장하지 못했어요. 저장 공간을 확인해 주세요.";
            DataResult<LrcLibClient.Lyrics> fetched = repository.lyrics(song, scope);
            done(scope, () -> {
                loadingLyrics = false;
                if (!fetched.isSuccess()) { error(fetched.failure, "lyrics"); outputs.put("original", fetched.failure.message); }
                else { lyrics = fetched.value; outputs.put("original", lyrics.plain); }
                storageWarning();
                if (lyrics != null) { if (!"original".equals(detailTab)) generateSelected(); else drainOutputs(); }
            });
        });
    }
    public void closeDetail() {
        OperationScope d = operations.remove("detail"); if (d != null) d.cancel();
        OperationScope ai = operations.remove("ai"); if (ai != null) ai.cancel();
        selected = null; loadingLyrics = false; loadingAi = false; pendingOutputs.clear(); aiStatus = ""; failure = null; notifyUi();
    }
    public void selectTab(String tab) {
        detailTab = tab; notifyUi();
        if (!"original".equals(tab) && lyrics != null && !outputs.containsKey(tab)) generateSelected();
    }
    public void generateSelected() {
        if ("translation".equals(detailTab) || "pronunciation".equals(detailTab)) {
            pendingOutputs.add(detailTab);
        }
        drainOutputs();
    }
    public void requestOutputs(boolean translation, boolean pronunciation) {
        if (translation) pendingOutputs.add("translation");
        if (pronunciation) pendingOutputs.add("pronunciation");
        drainOutputs();
    }
    public void cancelAi() {
        OperationScope scope = operations.remove("ai"); if (scope != null) scope.cancel();
        pendingOutputs.clear(); loadingAi = false; aiStatus = "생성을 취소했어요"; notifyUi();
    }
    public void retryOutput(String kind) {
        outputErrors.remove(kind); outputs.remove(kind); pendingOutputs.add(kind); drainOutputs();
    }
    private void drainOutputs() {
        if (selected == null || lyrics == null || loadingAi) return;
        String next = null;
        for (String kind : new ArrayList<>(pendingOutputs)) {
            pendingOutputs.remove(kind);
            if (!outputs.containsKey(kind) && !outputErrors.containsKey(kind)) { next = kind; break; }
        }
        if (next == null) return;
        String kind = next, text = lyrics.plain;
        SongInfo song = selected;
        String provider = settings.getAiProvider();
        String lang = settings.resolvedLanguage(), model = settings.getModel(provider), keys = settings.getApiKeys(provider);
        OperationScope scope = start("ai");
        scope.onProgress(message -> done(scope, () -> aiStatus = message));
        loadingAi = true; aiStatus = AppSettings.providerLabel(provider) + " · 요청 준비 중"; notifyUi();
        workers.execute(() -> {
            long token = store.generation();
            LyricsAiClient.AiTextResult result = repository.ai(song, text, lang, provider, model, keys, kind, scope);
            if (!scope.isCancelled()) store.write("diagnostics", "last-ai", scope.diagnostics, token);
            done(scope, () -> {
                loadingAi = false;
                if (result.isSuccess()) {
                    aiStatus = "완료"; outputs.put(kind, result.getText()); outputErrors.remove(kind);
                    outputSources.put(kind, AppSettings.providerLabel(result.usedProvider)
                            + (result.routingNotice.isEmpty() ? "" : "\n" + result.routingNotice));
                } else {
                    aiStatus = result.getFailure().title; outputErrors.put(kind, result.getErrorMessage());
                    failedOutput = kind; error(result.getFailure(), "ai"); pendingOutputs.clear();
                }
                storageWarning();
                if (result.isSuccess()) drainOutputs();
            });
        });
    }
    public String diagnosticSummary() {
        String[] lines = store.read("diagnostics", "last-ai", String[].class, 0);
        return lines == null || lines.length == 0 ? "최근 AI 요청 진단 기록이 없어요. 캐시를 사용한 요청은 서버를 호출하지 않아요."
                : "제공자 | 모델 | 결과 코드 | 소요 시간\n" + LyricLines.join(lines)
                  + "\nAPI 키·가사·요청 본문은 기록하지 않아요.";
    }
    public void retry() {
        if ("ai".equals(failureOperation)) { retryOutput(failedOutput); }
        else if ("lyrics".equals(failureOperation) && selected != null) open(selected, source);
        else if ("weekly".equals(failureOperation)) loadWeekly();
        else if ("settings".equals(failureOperation)) testKey();
        else if ("recognition".equals(failureOperation)) recognize();
        else if ("current".equals(failureOperation)) current(InternalPlaybackListenerService.getLastSongInfo());
        else search(false);
    }
    public void loadWeekly() {
        if (loadingWeekly) return;
        OperationScope scope = start("weekly"); loadingWeekly = true; notifyUi();
        workers.execute(() -> {
            DataResult<MusicStore.Weekly> result = repository.weekly(scope);
            done(scope, () -> { loadingWeekly = false; if (result.isSuccess()) weekly = result.value; else error(result.failure, "weekly"); storageWarning(); });
        });
    }
    public void recognize() {
        if (!BuildConfig.ACR_RECOGNITION_ENABLED) {
            error(new AiFailure("ACR_DISABLED", "주변 음악 인식은 준비 중이에요", "자격정보를 설정에 미리 저장할 수 있어요. 현재는 녹음하거나 서버를 호출하지 않아요.", AiFailure.Action.SETTINGS), "recognition"); notifyUi(); return;
        }
        OperationScope scope = start("recognition"); recognizing = true;
        recognitionStatus = "마이크로 최대 10초간 듣고 곡을 확인해요"; notifyUi();
        workers.execute(() -> {
            DataResult<SongInfo> result = new RecognitionController().recognize(settings, scope);
            done(scope, () -> { recognizing = false; recognitionStatus = ""; if (result.isSuccess()) current(result.value); else error(result.failure, "recognition"); });
        });
    }
    public void stopRecognition() {
        OperationScope scope = operations.remove("recognition"); if (scope != null) scope.cancel();
        if (recognizing) { recognizing = false; recognitionStatus = "인식을 취소했어요"; notifyUi(); }
    }
    public void saveDraft() {
        if (draft == null) return;
        if (!draft.original && !draft.translation && !draft.pronunciation) {
            error(new AiFailure("OUTPUT", "표시 항목을 선택해 주세요", "원본·번역·발음 중 하나 이상을 켜 주세요.", AiFailure.Action.SETTINGS), "settings"); notifyUi(); return;
        }
        try {
            if (!draft.model.matches("openai".equals(draft.provider) ? "[A-Za-z0-9][A-Za-z0-9._:-]*" : "gemini-[A-Za-z0-9._-]+")) {
                settingsStatus = "모델 ID를 확인해 주세요. 모델 목록에서 선택할 수 있어요."; notifyUi(); return;
            }
            if (!draft.acrHost.isEmpty() || !draft.acrKey.isEmpty() || !draft.acrSecret.isEmpty()) {
                String invalid = RecognitionConfig.validate(draft.acrHost, draft.acrKey, draft.acrSecret);
                if (!invalid.isEmpty()) { settingsStatus = invalid; notifyUi(); return; }
            }
            settings.saveAcr(draft.acrHost, draft.acrKey, draft.acrSecret);
            draft.providerKeys.put(draft.provider, draft.keys); draft.providerModels.put(draft.provider, draft.model);
            settings.saveUiSettings(draft.providerKeys.get("gemini"), draft.providerModels.get("gemini"), draft.language, draft.original, draft.translation, draft.pronunciation);
            settings.saveProvider("openai", draft.providerKeys.get("openai"), draft.providerModels.get("openai"));
            settings.selectProvider(draft.provider);
            invalidateAi();

            settings.setAiCleanupEnabled(draft.cleanup); settingsStatus = "설정을 저장했어요";
        } catch (IllegalStateException e) { settingsStatus = e.getMessage(); }
        notifyUi();
    }
    public List<LyricsAiClient.ModelOption> models = new ArrayList<>();
    public void testKey() { runSettings(true); }
    public void refreshModels() { runSettings(false); }
    private void runSettings(boolean test) {
        if (draft == null) return;
        OperationScope scope = start("settings"); workingSettings = true;
        String keys = draft.keys, model = draft.model, provider = draft.provider;
        settingsStatus = test ? "실제 생성 요청으로 연결을 확인하고 있어요" : "모델을 불러오고 있어요"; notifyUi();
        workers.execute(() -> {
            LyricsAiClient client = new LyricsAiClient(scope, provider);
            if (test) {
                LyricsAiClient.AiTextResult result = client.testGeneration(keys, model);
                done(scope, () -> { workingSettings = false; settingsStatus = result.isSuccess() ? "생성 테스트 성공. 긴 가사 번역은 사용량에 따라 실패할 수 있어요." : result.getErrorMessage(); if (!result.isSuccess()) error(result.getFailure(), "settings"); });
            } else {
                LyricsAiClient.ModelFetchResult result = client.fetchAvailableModels(keys);
                done(scope, () -> { workingSettings = false; settingsStatus = result.getMessage(); if (result.isSuccess()) models = result.getModels(); });
            }
        });
    }
    public void clear(boolean all) {
        for (OperationScope op : operations.values()) op.cancel();
        operations.clear(); searching = loadingAi = loadingLyrics = loadingWeekly = workingSettings = false;
        recognizing = false; selected = null; weekly = null; results.clear(); outputs.clear(); outputSources.clear(); outputErrors.clear(); pendingOutputs.clear(); aiStatus = "";
        storageNotice = store.clear(all) ? "데이터를 초기화했어요" : "일부 데이터를 지우지 못했어요";
        notifyUi();
    }
    public void toggleSaved() {
        if (selected != null && !store.toggleSaved(selected)) storageNotice = "보관 상태를 저장하지 못했어요";
        notifyUi();
    }
    private void storageWarning() { if (repository.storageFailed) storageNotice = "화면은 표시했지만 데이터를 저장하지 못했어요. 저장 공간을 확인해 주세요."; }
    @Override protected void onCleared() {
        for (OperationScope op : operations.values()) op.cancel();
        workers.shutdownNow();
    }
}
