package com.example.lyrics_mobile;

import android.content.Context;
import android.os.SystemClock;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class GenerationConsentTest {
    private void inject(MusicViewModel vm, MusicRepository repository) {
        try {
            java.lang.reflect.Field field = MusicViewModel.class.getDeclaredField("repository");
            field.setAccessible(true); field.set(vm, repository);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    private void settle() {
        SystemClock.sleep(700);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    private void capture(String name) {
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.io.File file = new java.io.File(context.getExternalFilesDir(null), name);
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
            try (android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .executeShellCommand("cp " + file.getAbsolutePath() + " /sdcard/Download/" + name);
                 java.io.FileInputStream in = new java.io.FileInputStream(fd.getFileDescriptor())) { while (in.read() != -1) { } }
        } catch (Exception e) { throw new AssertionError(e); }
        finally { bitmap.recycle(); }
    }
    @Test public void openingAndRecreatingCombinedReaderNeverGeneratesUntilExplicitSelection() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ReaderPreferences prefs = new ReaderPreferences(context);
        boolean combined = prefs.combined(), pre = prefs.preGenerate();
        boolean original = prefs.original(), translation = prefs.translation(), pronunciation = prefs.pronunciation();
        boolean serif = prefs.serif(); int size = prefs.size(), spacing = prefs.spacing();
        prefs.combined(true); prefs.preGenerate(false); prefs.save(true, true, true, serif, size, spacing);
        AtomicInteger calls = new AtomicInteger();
        SongInfo song = new SongInfo("Consent " + System.nanoTime(), "Test artist");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                inject(vm, new MusicRepository(vm.store, vm.settings, (scope, provider) -> new LyricsAiClient(scope, provider) {
                    @Override public AiTextResult translateLyrics(String text, String lang, String keys, String model) {
                        calls.incrementAndGet(); return new AiTextResult(true, "테스트 번역", "");
                    }
                    @Override public AiTextResult createPronunciationGuide(String text, String lang, String keys, String model) {
                        calls.incrementAndGet(); return new AiTextResult(true, "테스트 발음", "");
                    }
                }));
                LrcLibClient.Lyrics lyrics = new LrcLibClient.Lyrics(); lyrics.plain = "テストの歌";
                assertTrue(vm.store.write("lyrics", "native-v2:" + song.identity(), lyrics, vm.store.generation()));
                vm.open(song, "test");
            });
            settle(); assertEquals(0, calls.get());
            capture("lyrics-generation-pending.png");
            scenario.recreate(); settle(); assertEquals(0, calls.get());
            scenario.onActivity(activity -> new ViewModelProvider(activity).get(MusicViewModel.class).selectTab("translation"));
            settle(); assertEquals(1, calls.get());
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                assertEquals("테스트 번역", vm.outputs.get("translation"));
                vm.closeDetail(); vm.open(song, "test");
            });
            settle(); assertEquals(1, calls.get());
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                assertEquals("테스트 번역", vm.outputs.get("translation"));
                assertFalse(vm.outputs.containsKey("pronunciation")); vm.closeDetail();
            });
        } finally {
            prefs.save(original, translation, pronunciation, serif, size, spacing);
            prefs.combined(combined); prefs.preGenerate(pre);
        }
    }
    @Test public void recoveryPopupCancelDoesNotCallAiAndConfirmDoesOnce() {
        AtomicInteger calls = new AtomicInteger();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppSettings settings = new AppSettings(context);
        String provider = settings.getAiProvider(), key = settings.getApiKeys(provider), model = settings.getModel(provider);
        settings.saveProvider(provider, "fake-consent-test", model);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                inject(vm, new MusicRepository(vm.store, vm.settings, (scope, selected) -> new LyricsAiClient(scope, selected) {
                    @Override public AiTextResult cleanMetadata(String title, String artist, String keys, String model) {
                        calls.incrementAndGet(); return new AiTextResult(true, "{\"title\":\"Test\",\"artist\":\"Artist\"}", "");
                    }
                }));
                vm.selected = new SongInfo("Test", "Artist", "", "test-consent", "", 180000); vm.lyrics = null;
                vm.lyricsMissing = true; vm.aiRecoveryAttempted = false; vm.failureOperation = "lyrics";
                vm.failure = null; vm.notifyUi();
            });
            settle(); onView(withText("AI로 추가 탐색")).perform(click());
            settle(); capture("lyrics-recovery-consent.png");
            onView(withText("취소")).perform(click()); settle(); assertEquals(0, calls.get());
            onView(withText("AI로 추가 탐색")).perform(click());
            onView(withText("AI로 찾아보기")).perform(click()); settle(); assertEquals(1, calls.get());
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                assertTrue(vm.aiRecoveryAttempted); assertNull(vm.lyrics); assertFalse(vm.loadingAi); vm.closeDetail();
            });
        } finally { settings.saveProvider(provider, key, model); }
    }
    @Test public void cacheOnlyPreparationDoesNotConstructAiClient() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MusicStore store = new MusicStore(context); AppSettings settings = new AppSettings(context);
        MusicRepository repository = new MusicRepository(store, settings, (s,p) -> { throw new AssertionError("Unexpected AI client"); });
        SongInfo song = new SongInfo("Cache only " + System.nanoTime(), "Test");
        assertNull(repository.cachedAi(song, "original", "ko", "openai", "fake-model", "translation"));
        String key = MusicStore.variant(song, "original", "ko", "openai:fake-model", "translation");
        assertTrue(store.write("ai", key, "cached", store.generation()));
        assertEquals("cached", repository.cachedAi(song, "original", "ko", "openai", "fake-model", "translation"));
    }
    @Test public void serviceFailureRequiresDirectConsentAndReusesSeparateCache() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppSettings settings = new AppSettings(context);
        String provider = settings.getAiProvider(), key = settings.getApiKeys(provider), selectedModel = settings.getModel(provider);
        settings.saveProvider(provider, "fake-direct-test", selectedModel);
        AtomicInteger calls = new AtomicInteger();
        SongInfo song = new SongInfo("Direct " + System.nanoTime(), "Test artist");
        MusicRepository[] repository = new MusicRepository[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                repository[0] = new MusicRepository(vm.store, vm.settings, (scope, p) -> new LyricsAiClient(scope, p) {
                    @Override public AiTextResult requestLyrics(SongInfo s, String keys, String model) {
                        assertEquals(provider, p); assertEquals("fake-direct-test", keys);
                        calls.incrementAndGet();
                        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
                        json.addProperty("status", "ok"); json.addProperty("title", s.getTitle());
                        json.addProperty("artist", s.getArtist()); json.addProperty("lyrics", "合成テストの歌詞");
                        return new AiTextResult(true, json.toString(), "");
                    }
                });
                inject(vm, repository[0]);
                vm.selected = song; vm.failureOperation = "lyrics"; vm.failure = DataResult.service(503, "LRCLIB"); vm.notifyUi();
            });
            settle(); assertEquals(0, calls.get()); capture("direct-ai-503-consent.png");
            onView(withText("취소")).perform(click()); settle(); assertEquals(0, calls.get());
            onView(withText("AI에 가사 직접 요청")).perform(click());
            onView(withText("확인 · AI 요청")).perform(click()); settle(); assertEquals(1, calls.get());
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                assertNotNull(vm.lyrics); assertTrue(vm.lyrics.aiSupplied); assertEquals("", vm.lyrics.synced);
                assertFalse(vm.outputs.containsKey("translation")); assertFalse(vm.outputs.containsKey("pronunciation"));
                assertNull(vm.store.read("lyrics", "native-v2:" + song.identity(), LrcLibClient.Lyrics.class, 0));
                assertTrue(repository[0].requestAiLyrics(song, new OperationScope(), provider, selectedModel).isSuccess());
                assertEquals(1, calls.get());
            });
            capture("direct-ai-result.png");
        } finally { settings.saveProvider(provider, key, selectedModel); }
    }
}
