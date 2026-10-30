package com.example.lyrics_mobile;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MusicIntegrationTest {
    private Context context;
    private MusicStore store;
    @Before public void setup() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File testRoot = new File(target.getCacheDir(), "music-tests-" + System.nanoTime());
        context = new ContextWrapper(target) {
            @Override public File getFilesDir() { testRoot.mkdirs(); return testRoot; }
            @Override public File getExternalFilesDir(String type) { return null; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences("test_" + name, mode); }
        };
        store = new MusicStore(context);
    }
    @Test public void diskRoundTripAndCorruptionRecovery() throws Exception {
        assertTrue(store.write("ai", "test", "translated", store.generation()));
        assertEquals("translated", new MusicStore(context).read("ai", "test", String.class, MusicStore.MONTH));
        File file = new File(store.root(), "ai/" + MusicStore.hash("test") + ".json");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write("broken json".getBytes()); }
        assertNull(store.read("ai", "test", String.class, MusicStore.MONTH));
        assertTrue(store.write("ai", "test", "regenerated", store.generation()));
        assertEquals("regenerated", store.read("ai", "test", String.class, MusicStore.MONTH));
    }
    @Test public void resetRejectsInflightWritesAndPreservesLibraryOnCacheClear() {
        SongInfo song = new SongInfo("Test", "Artist");
        assertTrue(store.visit(song, "test")); assertTrue(store.toggleSaved(song));
        long token = store.generation(); assertTrue(store.clear(false));
        assertFalse(store.write("ai", "old", "stale", token));
        assertFalse(store.visit(new SongInfo("Stale", "Artist"), "test", token));
        assertTrue(store.isSaved(song)); assertTrue(store.clear(true)); assertTrue(store.library().songs.isEmpty());
    }
    @Test public void repeatedVisitsNeverDuplicateSavedSong() {
        SongInfo song = new SongInfo("Test", "Artist");
        store.visit(song,"test"); store.toggleSaved(song); store.visit(song,"test");
        assertEquals(1,store.library().songs.size()); assertTrue(store.isSaved(song));
    }
    @Test public void versionAndTtlInvalidation() throws Exception {
        File dir = new File(store.root(), "ai"); assertTrue(dir.mkdirs());
        File file = new File(dir,MusicStore.hash("test") + ".json");
        String old = "{\"schema\":2,\"savedAt\":1,\"value\":\"old\"}";
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(old.getBytes()); }
        assertNull(store.read("ai","test",String.class,MusicStore.WEEK));
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(old.replace("\"schema\":2", "\"schema\":99").getBytes()); }
        assertNull(store.read("ai","test",String.class,0));
    }
    @Test public void credentialsAreEncryptedAtRest() {
        SecureVault vault = new SecureVault(context);
        vault.put("test_key","not-a-real-secret");
        assertEquals("not-a-real-secret",vault.get("test_key"));
        assertFalse(context.getSharedPreferences("secure_credentials",0).getString("test_key", "").contains("not-a-real-secret"));
        vault.put("test_key", "");
    }
    @Test public void recognitionGateStopsBeforeRecordingOrNetwork() {
        assertFalse(BuildConfig.ACR_RECOGNITION_ENABLED);
        DataResult<SongInfo> result = new RecognitionController().recognize(new AppSettings(context), new OperationScope());
        assertFalse(result.isSuccess()); assertEquals("ACR_DISABLED",result.failure.code);
    }
    @Test public void providerCredentialsAndSelectionPersistSeparately() {
        AppSettings settings = new AppSettings(context);
        settings.saveProvider("gemini", "fake-gemini-key", "gemini-2.5-flash");
        settings.saveProvider("openai", "fake-openai-key", "gpt-4.1-mini");
        settings.selectProvider("openai");
        AppSettings restored = new AppSettings(context);
        assertEquals("openai", restored.getAiProvider());
        assertEquals("fake-gemini-key", restored.getApiKeys("gemini"));
        assertEquals("fake-openai-key", restored.getApiKeys("openai"));
        assertEquals("gpt-4.1-mini", restored.getModel("openai"));
        settings.saveProvider("gemini", "", "gemini-2.5-flash");
        settings.saveProvider("openai", "", "gpt-4.1-mini");
        settings.selectProvider("gemini");
    }
    @Test public void switchingProviderPreservesUnsavedInputsAcrossRecreation() {
        AppSettings previous = new AppSettings(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String geminiKeys = previous.getApiKeys("gemini"), gptKeys = previous.getApiKeys("openai");
        String geminiModel = previous.getModel("gemini"), gptModel = previous.getModel("openai"), provider = previous.getAiProvider();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                vm.navigate("settings"); vm.switchProvider("gemini"); vm.draft.keys = "draft-gemini";
                vm.switchProvider("openai"); vm.draft.keys = "draft-openai"; vm.draft.model = "gpt-4.1-mini";
                assertEquals("openai", new AppSettings(activity).getAiProvider());
                vm.switchProvider("gemini"); assertEquals("draft-gemini", vm.draft.keys);
                vm.switchProvider("openai"); assertEquals("draft-openai", vm.draft.keys);
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                assertEquals("openai", vm.draft.provider); assertEquals("draft-openai", vm.draft.keys);
                assertEquals("gpt-4.1-mini", vm.draft.model);
                vm.switchProvider("gemini"); assertEquals("draft-gemini", vm.draft.keys);
                vm.navigate("home");
                assertEquals("draft-gemini", new AppSettings(activity).getApiKeys("gemini"));
            });
        } finally {
            previous.saveProvider("gemini", geminiKeys, geminiModel); previous.saveProvider("openai", gptKeys, gptModel);
            previous.selectProvider(provider);
        }
    }
    @Test public void leavingSettingsAppliesGptWithoutSaveButtonAndKeepsGeminiKey() {
        AppSettings previous = new AppSettings(InstrumentationRegistry.getInstrumentation().getTargetContext());
        String geminiKeys = previous.getApiKeys("gemini"), gptKeys = previous.getApiKeys("openai");
        String geminiModel = previous.getModel("gemini"), gptModel = previous.getModel("openai"), provider = previous.getAiProvider();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                vm.navigate("settings"); vm.switchProvider("gemini"); vm.draft.keys = "fake-gemini-unpaid";
                vm.switchProvider("openai"); vm.draft.keys = "fake-gpt-paid"; vm.draft.model = "gpt-4.1-mini";
                vm.navigate("home");
                AppSettings applied = new AppSettings(activity);
                assertEquals("home", vm.page); assertEquals("openai", applied.getAiProvider());
                assertEquals("fake-gpt-paid", applied.getApiKeys(applied.getAiProvider()));
                assertEquals("fake-gemini-unpaid", applied.getApiKeys("gemini"));
                assertEquals("gpt-4.1-mini", applied.getModel(applied.getAiProvider()));
            });
        } finally {
            previous.saveProvider("gemini", geminiKeys, geminiModel); previous.saveProvider("openai", gptKeys, gptModel);
            previous.selectProvider(provider);
        }
    }
    @Test public void fallbackUsesSeparateKeysAndCacheWithoutOverridingGptPriority() {
        AppSettings settings = new AppSettings(context);
        settings.saveProvider("gemini", "fake-gemini", "gemini-2.5-flash");
        settings.saveProvider("openai", "fake-gpt", "gpt-4.1-mini");
        java.util.List<String> calls = new java.util.ArrayList<>();
        boolean[] gptWorks = {false};
        MusicRepository repository = new MusicRepository(store, settings, (scope, provider) -> new LyricsAiClient(scope, provider) {
            @Override public AiTextResult translateLyrics(String lyrics, String lang, String keys, String model) {
                calls.add(provider);
                assertEquals(provider.equals("openai") ? "fake-gpt" : "fake-gemini", keys);
                assertEquals(provider.equals("openai") ? "gpt-4.1-mini" : "gemini-2.5-flash", model);
                if (provider.equals("openai") && !gptWorks[0]) return new AiTextResult(OpenAiClient.httpFailure(429, "{}"));
                return new AiTextResult(true, provider + " result", "");
            }
        });
        try {
            SongInfo song = new SongInfo("Fallback test", "Artist");
            LyricsAiClient.AiTextResult backup = repository.ai(song, "line", "ko", "openai", "gpt-4.1-mini", "fake-gpt", "translation", new OperationScope());
            assertEquals(java.util.Arrays.asList("openai", "gemini"), calls); assertEquals("gemini", backup.usedProvider);
            calls.clear(); gptWorks[0] = true;
            LyricsAiClient.AiTextResult primary = repository.ai(song, "line", "ko", "openai", "gpt-4.1-mini", "fake-gpt", "translation", new OperationScope());
            assertEquals(java.util.Arrays.asList("openai"), calls); assertEquals("openai result", primary.getText());
            calls.clear();
            assertEquals("openai result", repository.ai(song, "line", "ko", "openai", "gpt-4.1-mini", "fake-gpt", "translation", new OperationScope()).getText());
            assertTrue(calls.isEmpty());
        } finally {
            settings.saveProvider("gemini", "", "gemini-2.5-flash"); settings.saveProvider("openai", "", "gpt-4.1-mini");
        }
    }
    @Test public void recreationKeepsDraftAndSelectedTabWithoutNewRequests() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                vm.navigate("settings"); vm.draft.model="test-model-retained"; vm.query="retained query";
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                assertEquals("test-model-retained",vm.draft.model); assertEquals("retained query",vm.query);
                assertEquals("settings",vm.page);
                vm.selected = new SongInfo("Rotation test", "Artist");
                vm.detailTab = "translation"; vm.outputs.put("translation", "Retained translation"); vm.notifyUi();
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                assertEquals("translation", vm.detailTab); assertEquals("Retained translation", vm.outputs.get("translation"));
                assertNotNull(vm.selected); vm.closeDetail();
                vm.navigate("library"); vm.navigate("home");
            });
        }
    }
}
