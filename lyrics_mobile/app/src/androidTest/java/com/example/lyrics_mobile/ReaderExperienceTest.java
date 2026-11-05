package com.example.lyrics_mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReaderExperienceTest {
    private static Object field(Object target, String name) {
        try { java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @Test public void combinedReaderReachesLastLineAndPreservesPositionAcrossRotation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ReaderPreferences prefs = new ReaderPreferences(context);
        boolean original = prefs.original(), translation = prefs.translation(), pronunciation = prefs.pronunciation(), serif = prefs.serif(), combined = prefs.combined();
        int size = prefs.size(), spacing = prefs.spacing();
        prefs.save(true, true, true, false, 22, 14); prefs.combined(true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                vm.selected = new SongInfo("읽기 화면 테스트", "샘플 아티스트"); vm.detailTab = "combined";
                LrcLibClient.Lyrics lyrics = new LrcLibClient.Lyrics();
                StringBuilder source = new StringBuilder(), translated = new StringBuilder(), phonetic = new StringBuilder();
                for (int i = 1; i <= 80; i++) {
                    if (i > 1) { source.append('\n'); translated.append('\n'); phonetic.append('\n'); }
                    source.append("音楽を楽しむ ").append(i); translated.append("음악을 즐겨요 ").append(i); phonetic.append("온가쿠오 타노시무 ").append(i);
                }
                lyrics.plain = source.toString(); vm.lyrics = lyrics;
                vm.outputs.put("original", lyrics.plain); vm.outputs.put("translation", translated.toString()); vm.outputs.put("pronunciation", phonetic.toString());
                vm.outputSources.put("translation", "OpenAI (GPT) · 테스트 데이터"); vm.outputSources.put("pronunciation", "OpenAI (GPT) · 테스트 데이터");
                vm.notifyUi();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ScrollView scroll = (ScrollView) field(activity, "lyricsScroll");
                assertTrue(scroll.getHeight() > 100); assertTrue(scroll.canScrollVertically(1));
                assertTrue(((TextView) field(activity, "detailBody")).getText().toString().contains("음악을 즐겨요 80"));
                int ancestorScrolls = 0; android.view.ViewParent parent = scroll.getParent();
                while (parent != null) { if (parent instanceof ScrollView) ancestorScrolls++; parent = parent.getParent(); }
                assertEquals(0, ancestorScrolls);
                android.view.ViewGroup tabs = (android.view.ViewGroup) field(activity, "detailTabs");
                for (int i = 0; i < tabs.getChildCount(); i++) {
                    assertEquals(tabs.getChildAt(0).getTop(), tabs.getChildAt(i).getTop());
                    assertTrue(tabs.getChildAt(i).getBottom() <= tabs.getHeight());
                }
            });
            capture(context, "lyrics-reader-top.png");
            scenario.onActivity(activity -> ((ScrollView) field(activity, "lyricsScroll")).fullScroll(View.FOCUS_DOWN));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ScrollView scroll = (ScrollView) field(activity, "lyricsScroll");
                scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
                assertTrue(scroll.getScrollY() > 0); assertFalse(scroll.canScrollVertically(1));
            });
            capture(context, "lyrics-reader-bottom.png");
            scenario.recreate(); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ScrollView scroll = (ScrollView) field(activity, "lyricsScroll"); assertTrue(scroll.getScrollY() > 0);
                MusicViewModel vm = new ViewModelProvider(activity).get(MusicViewModel.class);
                vm.loadingAi = true; vm.aiStatus = "OpenAI (GPT) · 번역 1/3 구간 처리 중"; vm.notifyUi();
                assertEquals(View.VISIBLE, ((View) field(activity, "loadingRow")).getVisibility());
                vm.cancelAi(); assertFalse(vm.loadingAi);
                vm.closeDetail();
            });
        } finally { prefs.save(original, translation, pronunciation, serif, size, spacing); prefs.combined(combined); }
    }
    private void capture(Context context, String name) throws Exception {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap); File file = new File(context.getExternalFilesDir(null), name);
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
        try (android.os.ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand("cp " + file.getAbsolutePath() + " /sdcard/Download/" + name);
             java.io.FileInputStream in = new java.io.FileInputStream(fd.getFileDescriptor())) { while (in.read() != -1) { } }
    }
}
