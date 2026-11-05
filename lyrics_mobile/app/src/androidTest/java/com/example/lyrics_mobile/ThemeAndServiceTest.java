package com.example.lyrics_mobile;

import android.content.Context;
import android.os.SystemClock;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class ThemeAndServiceTest {
    private void settle() { SystemClock.sleep(500); InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }
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
    @Test public void themesPersistAndServiceFailureOffersManualInput() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ReaderPreferences prefs = new ReaderPreferences(context);
        String previous = prefs.theme(); prefs.theme("charcoal");
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            settle(); capture("theme-charcoal.png");
            scenario.onActivity(a -> new ViewModelProvider(a).get(MusicViewModel.class).navigate("settings"));
            settle(); onView(withText("오렌지 · 그린")).perform(click()); settle();
            assertEquals("orange_green", prefs.theme());
            scenario.recreate(); settle();
            onView(withText("오렌지 · 그린 · 선택됨")).check(matches(isDisplayed()));
            scenario.onActivity(a -> new ViewModelProvider(a).get(MusicViewModel.class).navigate("home"));
            settle(); capture("theme-orange-green.png");
            scenario.onActivity(a -> {
                MusicViewModel vm = new ViewModelProvider(a).get(MusicViewModel.class);
                vm.selected = new SongInfo("테스트 곡", "테스트 아티스트");
                vm.failure = DataResult.service(503, "LRCLIB 가사 서버");
                vm.outputs.put("original", vm.failure.message);
                vm.failureOperation = "lyrics";
                vm.notifyUi();
            });
            settle();
            onView(withText("확인 · AI 요청")).check(matches(isDisplayed()));
            onView(withText("다시 시도")).check(matches(isDisplayed()));
            capture("lyrics-service-503.png");
            onView(withText("취소")).perform(click()); settle();
            onView(withText("가사 직접 붙여넣기")).perform(click()); settle();
            onView(withHint("사용할 권한이 있는 원문 가사를 붙여넣으세요")).check(matches(isDisplayed()));
        } finally { prefs.theme(previous); }
    }
    @Test public void oldOriginalCacheSurvivesPruningAndWorksWithoutNetwork() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MusicStore store = new MusicStore(context);
        SongInfo song = new SongInfo("Old cache " + System.nanoTime(), "Test");
        String key = "native-v2:" + song.identity();
        LrcLibClient.Lyrics lyrics = new LrcLibClient.Lyrics(); lyrics.plain = "保存したテスト";
        assertTrue(store.write("lyrics", key, lyrics, store.generation()));
        java.io.File file = new java.io.File(store.root(), "lyrics/" + MusicStore.hash(key) + ".json");
        try {
            com.google.gson.JsonObject json;
            try (java.io.FileReader in = new java.io.FileReader(file)) { json = com.google.gson.JsonParser.parseReader(in).getAsJsonObject(); }
            long old = System.currentTimeMillis() - MusicStore.MONTH * 2;
            json.addProperty("savedAt", old);
            try (java.io.FileWriter out = new java.io.FileWriter(file)) { out.write(json.toString()); }
            assertTrue(file.setLastModified(old));
            assertTrue(store.write("diagnostics", "prune-test", "test", store.generation()));
            assertTrue(file.exists());
            OperationScope cancelled = new OperationScope(); cancelled.cancel();
            DataResult<LrcLibClient.Lyrics> result = new MusicRepository(store, new AppSettings(context)).lyrics(song, cancelled);
            assertTrue(result.isSuccess()); assertEquals(lyrics.plain, result.value.plain);
        } finally { file.delete(); }
    }
}
