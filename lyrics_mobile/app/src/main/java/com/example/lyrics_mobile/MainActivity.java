package com.example.lyrics_mobile;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends AppCompatActivity implements InternalPlaybackListenerService.PlaybackListener {
    private AppPalette palette = AppPalette.charcoal();
    private int BG = palette.background, SURFACE = palette.surface, TEXT = palette.text, MUTED = palette.muted, ACCENT = palette.accent;
    private MusicViewModel model;
    private LinearLayout root, content;
    private ScrollView scroll;
    private final Map<String, Integer> offsets = new HashMap<>();
    private String renderedPage = "";
    private String animatedResults = "", bodyMode = "";
    private Dialog detail;
    private SongInfo dialogSong;
    private TextView detailBody, detailStatus;
    private LinearLayout detailTabs;
    private ReaderPreferences reader;
    private ScrollView lyricsScroll;
    private LinearLayout loadingRow, readerErrors;
    private String errorSignature = "";
    private boolean showAcr;
    private TextView loadingLabel;
    private MaterialButton cancelGeneration;
    private String bodySignature = "", tabsSignature = "", requestedOutputs = "";
    private int restoredReaderY;
    private String restoredReaderSong = "", restoredRequest = "";
    private final Map<String, Integer> readerOffsets = new HashMap<>();

    private boolean rebuilding;
    private Dialog errorDialog;
    private SongInfo current;

    @Override protected void onCreate(Bundle state) {
        reader = new ReaderPreferences(this);
        palette = AppPalette.forId(reader.theme());
        BG = palette.background; SURFACE = palette.surface; TEXT = palette.text; MUTED = palette.muted; ACCENT = palette.accent;
        setTheme("orange_green".equals(reader.theme()) ? R.style.Theme_Lyrics_mobile_Orange : R.style.Theme_Lyrics_mobile);
        super.onCreate(state);
        model = new ViewModelProvider(this).get(MusicViewModel.class);
        if (state != null && model.selected == null && model.query.isEmpty()) {
            model.page = state.getString("page", "home"); model.query = state.getString("query", "");
            String restored = state.getString("song", "");
            if (!restored.isEmpty()) {
                try { model.open(new com.google.gson.Gson().fromJson(restored, SongInfo.class), "이전 화면"); }
                catch (RuntimeException ignored) { }
            }
        }
        if (state != null) {
            restoredReaderY = state.getInt("reader_y", 0); restoredRequest = state.getString("reader_request", "");
            restoredReaderSong = state.getString("reader_song", "");
            for (String page : new String[]{"home", "library", "weekly", "settings"}) offsets.put(page, state.getInt("scroll_" + page, 0));
        }
        current = InternalPlaybackListenerService.getLastSongInfo();

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int frame = "orange_green".equals(reader.theme()) ? dp(10) : 0;
            view.setPadding(bars.left + frame, bars.top + frame, bars.right + frame, Math.max(bars.bottom, ime.bottom) + frame);
            return insets;
        });
        boolean orange = "orange_green".equals(reader.theme());
        ViewCompat.getWindowInsetsController(root).setAppearanceLightStatusBars(orange);
        ViewCompat.getWindowInsetsController(root).setAppearanceLightNavigationBars(orange);
        getWindow().setNavigationBarColor(BG);
        model.changes.observe(this, ignored -> render());
    }
    @Override protected void onResume() {
        super.onResume(); InternalPlaybackListenerService.setPlaybackListener(this);
        current = InternalPlaybackListenerService.getLastSongInfo(); InternalPlaybackListenerService.requestRefresh();
        if (root != null) render();
    }
    @Override protected void onPause() { super.onPause(); InternalPlaybackListenerService.setPlaybackListener(null); }
    @Override protected void onStop() { model.stopRecognition(); super.onStop(); }
    @Override protected void onDestroy() {
        rebuilding = true;
        if (detail != null) { detail.setOnDismissListener(null); detail.dismiss(); }
        if (errorDialog != null) errorDialog.dismiss();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("page", model.page); out.putString("query", model.query);
        if (model.selected != null) out.putString("song", new com.google.gson.Gson().toJson(model.selected));
        if (lyricsScroll != null && dialogSong != null) {
            out.putString("reader_request", requestedOutputs); out.putInt("reader_y", lyricsScroll.getScrollY()); out.putString("reader_song", dialogSong.identity());
        }
        if (scroll != null) offsets.put(renderedPage, scroll.getScrollY());
        for (Map.Entry<String, Integer> entry : offsets.entrySet()) out.putInt("scroll_" + entry.getKey(), entry.getValue());

    }
    @Override public void onSongChanged(SongInfo song) {
        runOnUiThread(() -> { current = song; if ("home".equals(model.page)) render(); });
    }
    private void render() {
        if (isFinishing() || isDestroyed()) return;
        boolean pageChanged = !renderedPage.equals(model.page);
        if (detail != null && model.selected != null && renderedPage.equals(model.page)) {
            renderDetail();
            AiFailure pending = model.failure; model.failure = null;
            if (pending != null) showFailure(pending);
            return;
        }

        if (scroll != null) offsets.put(renderedPage, scroll.getScrollY());
        root.removeAllViews();
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        root.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout nav = navigation(landscape);
        if (landscape) root.addView(nav, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.MATCH_PARENT));
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = column(); content.setBackgroundColor(SURFACE); content.setPadding(dp(24), dp(20), dp(24), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(landscape ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                landscape ? ViewGroup.LayoutParams.MATCH_PARENT : 0, 1));
        if (!landscape) root.addView(nav);
        renderedPage = model.page;
        switch (model.page) {
            case "library": library(); break;
            case "weekly": weekly(); break;
            case "settings": settings(); break;
            default: home();
        }
        String resultKey = model.query + ":" + model.results.size() + (model.results.isEmpty() ? "" : model.results.get(0).identity());
        if (pageChanged) UiMotion.enter(content, 8, 0, reader.motion());
        else if (!model.searching && !resultKey.equals(animatedResults)) {
            int index = 0;
            for (int i = 0; i < content.getChildCount() && index < 6; i++) {
                View child = content.getChildAt(i);
                if ("song_row".equals(child.getTag())) UiMotion.enter(child, 10, index++ * 30L, reader.motion());
            }
        }
        animatedResults = resultKey;
        Integer y = offsets.get(model.page); if (y != null) scroll.post(() -> scroll.scrollTo(0, y));
        renderDetail();
        AiFailure failure = model.failure; model.failure = null;
        if (failure != null) showFailure(failure);
    }
    private LinearLayout navigation(boolean vertical) {
        LinearLayout nav = new LinearLayout(this); nav.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(SURFACE); nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        String[] ids = {"home", "library", "weekly", "settings"}; String[] labels = {"찾기", "보관함", "주간 추천", "설정"};
        for (int i = 0; i < ids.length; i++) {
            String target = ids[i]; MaterialButton button = button(labels[i], false, () -> model.navigate(target));
            button.setTextColor(target.equals(model.page) ? ACCENT : MUTED);
            button.setTextSize(13); button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(4), 0, dp(4), 0);
            button.setBackgroundTintList(ColorStateList.valueOf(SURFACE)); button.setSelected(target.equals(model.page));
            button.setStrokeWidth(0);
            button.setContentDescription(labels[i] + (target.equals(model.page) ? ", 선택됨" : ""));
            nav.addView(button, new LinearLayout.LayoutParams(vertical ? ViewGroup.LayoutParams.MATCH_PARENT : 0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, vertical ? 0 : 1));
        }
        return nav;
    }
    private void home() {
        heading(content, "음악 찾기", 32);
        add(content, text("지금 듣는 곡, 바로 펼쳐봐.", 15, MUTED));
        TextView now = text(current == null ? "현재 재생 중인 곡이 없어요" : current.getTitle() + "\n" + current.getArtist(), 16, MUTED);
        now.setPadding(0, dp(8), 0, dp(8)); add(content, now);
        MaterialButton detect = button("곡 찾기", true, () -> {
            if (!hasAccess()) new AlertDialog.Builder(this).setTitle("알림 접근 권한이 필요해요")
                    .setMessage("재생 중인 음악의 곡 정보를 가져오는 데 사용해요.")
                    .setPositiveButton("권한 설정", (d,w) -> openAccess()).setNegativeButton("닫기", null).show();
            else model.current(current);
        });
        detect.setTextSize(17); detect.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        detect.setPadding(dp(18), dp(18), dp(18), dp(18));
        detect.setMaxLines(1);
        WaveIconDrawable wave = new WaveIconDrawable(palette.onAccent);
        wave.setBounds(0, 0, dp(54), dp(54));
        detect.setCompoundDrawables(null, wave, null, null);
        detect.setCompoundDrawablePadding(dp(18)); detect.setGravity(Gravity.CENTER);
        detect.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        detect.setCornerRadius(dp(120)); detect.setInsetTop(0); detect.setInsetBottom(0);
        detect.setContentDescription("현재 재생 곡 가져오기. 플레이어의 곡 정보를 사용해요.");
        LinearLayout.LayoutParams detectParams = new LinearLayout.LayoutParams(dp(184), dp(184));
        detectParams.gravity = Gravity.CENTER_HORIZONTAL; detectParams.topMargin = dp(20); detectParams.bottomMargin = dp(8);
        content.addView(detect, detectParams);
        TextView hint = text("주변 소리 대신 플레이어 정보를 확인해요", 12, MUTED); hint.setGravity(Gravity.CENTER); add(content, hint);
        add(content, button(model.recognizing ? "듣기 취소" : "주변 음악 듣고 찾기" + (BuildConfig.ACR_RECOGNITION_ENABLED ? "" : " · 준비 중"), false, () -> {
            if (model.recognizing) model.stopRecognition();
            else if (!BuildConfig.ACR_RECOGNITION_ENABLED) model.recognize();
            else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 61);
            else model.recognize();
        }));
        if (!model.recognitionStatus.isEmpty()) add(content, text(model.recognitionStatus, 14, MUTED));
        heading(content, "노래 검색", 22);
        EditText search = input("노래 제목 또는 아티스트", model.query, false, value -> model.query = value);
        search.setSingleLine(true); search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v,a,event) -> { if (a == EditorInfo.IME_ACTION_SEARCH) { model.search(false); return true; } return false; });
        add(content, search);
        MaterialButton searchButton = button("검색", true, () -> model.search(false));
        searchButton.setEnabled(!model.searching); add(content, searchButton);
        if (model.searching) add(content, progress(model.searchMessage));
        if (model.searching) add(content, button("검색 취소", false, () -> model.cancelSearch()));
        if (!model.searching && !model.searchMessage.isEmpty()) add(content, text(model.searchMessage, 14, MUTED));
        for (SongInfo song : model.results) songRow(content, song, "검색");
        if (model.results.isEmpty() && !model.searching) {
            int recent = 0;
            for (MusicStore.Visit visit : model.store.library().songs) {
                if (recent == 0) heading(content, "다시 펼쳐보기", 20);
                songRow(content, visit.song, "최근 기록"); if (++recent == 3) break;
            }
        }
    }
    private void songRow(LinearLayout parent, SongInfo song, String from) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(12), dp(8), dp(12)); row.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33ffffff), background(SURFACE, 4), null));
        row.addView(new ArtworkView(this, song, ACCENT, palette.panel), new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout labels = column(); labels.setPadding(dp(16), 0, dp(8), 0);
        TextView title = text(song.getTitle(), 19, TEXT); title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END); labels.addView(title);
        TextView artist = text(song.getArtist(), 14, MUTED); artist.setMaxLines(1); artist.setEllipsize(android.text.TextUtils.TruncateAt.END); labels.addView(artist);
        long seconds = song.getDurationMs() / 1000;
        TextView meta = text(song.getAlbum() + (seconds > 0 ? " · " + seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60) : ""), 12, MUTED);
        meta.setMaxLines(1); meta.setEllipsize(android.text.TextUtils.TruncateAt.END); labels.addView(meta);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("↗", 22, ACCENT); arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(arrow);
        row.setFocusable(true); row.setClickable(true); row.setMinimumHeight(dp(72));
        row.setTag("song_row"); UiMotion.press(row, reader.motion());
        row.setContentDescription(song.getTitle() + ", " + song.getArtist() + ", 곡 상세 열기");
        row.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (TrackRules.alternateVersion(song.getTitle())) {
                new AlertDialog.Builder(this).setTitle("원곡으로 찾아볼까요?").setMessage("커버·라이브·리믹스는 원곡 정보와 원곡 가사만 제공해요. 검색 결과에서 원곡을 확인해 주세요.")
                        .setPositiveButton("원곡 후보 찾기", (d,w) -> model.current(song))
                        .setNegativeButton("닫기", null).show();
            } else model.open(song, from);
        });
        add(parent, row);
    }
    private void library() {
        heading(content, "보관함", 28);
        add(content, button(model.savedOnly ? "최근 기록 보기" : "저장한 곡만 보기", false, () -> { model.savedOnly = !model.savedOnly; model.notifyUi(); }));
        int count = 0;
        for (MusicStore.Visit visit : model.store.library().songs) if (!model.savedOnly || visit.saved) {
            songRow(content, visit.song, "보관함"); count++;
        }
        if (count == 0) add(content, text("아직 곡이 없어요. 곡 상세를 열면 기록이 남고, 저장 버튼으로 보관할 수 있어요.", 16, MUTED));
    }
    private void weekly() {
        heading(content, "주간 추천", 28);
        add(content, text("최근 찾아본 아티스트를 바탕으로 골랐어요. 추천은 7일마다 새로 만들어요.", 16, MUTED));
        if (model.loadingWeekly) add(content, progress("추천을 불러오는 중"));
        if (model.weekly != null) {
            add(content, text("다음 갱신: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(model.weekly.generatedAt + MusicStore.WEEK)), 14, MUTED));
            for (SongInfo song : model.weekly.songs) songRow(content, song, "주간 추천");
        } else if (!model.loadingWeekly) add(content, button("추천 불러오기", true, model::loadWeekly));
    }
    private void settings() {
        if (model.draft == null) model.loadDraft();
        MusicViewModel.Draft d = model.draft;
        heading(content, "설정", 28);
        heading(content, "테마", 22);
        add(content, text("색상만 바뀌며 가사·API 설정은 그대로 유지돼요.", 14, MUTED));
        add(content, button("차콜 · 라임 (B안)" + ("charcoal".equals(reader.theme()) ? " · 선택됨" : ""), "charcoal".equals(reader.theme()), () -> selectTheme("charcoal")));
        add(content, button("오렌지 · 그린" + ("orange_green".equals(reader.theme()) ? " · 선택됨" : ""), "orange_green".equals(reader.theme()), () -> selectTheme("orange_green")));
        add(content, text("오렌지 · 그린은 Vaundy의 색상 조합에서 영감을 받은 비공식 테마예요.", 12, MUTED));
        heading(content, "AI 번역", 22);
        add(content, button("AI 제공자: " + AppSettings.providerLabel(d.provider), false, () ->
                new AlertDialog.Builder(this).setTitle("AI 제공자").setItems(new String[]{"Gemini", "OpenAI (GPT)"},
                        (dialog, which) -> model.switchProvider(which == 0 ? "gemini" : "openai")).show()));
        labelInput(AppSettings.providerLabel(d.provider) + " API 키", "여러 키는 줄바꿈으로 구분", d.keys, true, value -> d.keys = value);
        add(content, text("AI 선택은 즉시 적용돼요. 키·모델은 다른 메뉴로 이동하면 자동 저장해요. 번역·발음 요청에서 GPT 실패 시 등록된 Gemini로 한 번 더 시도해요. 대체 요청도 API 사용량에 포함될 수 있어요. 연결 테스트는 선택한 AI만 확인해요.", 14, MUTED));
        add(content, text("키는 기기 보안 저장소로 암호화해 보관해요. 연결 테스트도 API 사용량에 포함돼요.", 14, MUTED));
        labelInput("모델", "모델 ID", d.model, false, value -> d.model = value);
        add(content, button("모델 목록 불러오기", false, model::refreshModels));
        if (!model.models.isEmpty()) add(content, button("목록에서 모델 선택", false, () -> {
            String[] names = new String[model.models.size()]; for (int i=0;i<names.length;i++) names[i] = model.models.get(i).getName();
            new AlertDialog.Builder(this).setTitle("모델 선택").setItems(names, (dialog,which) -> { d.model = model.models.get(which).getId(); model.notifyUi(); }).show();
        }));
        MaterialButton test = button("API 연결 테스트", false, model::testKey); test.setEnabled(!model.workingSettings); add(content, test);
        if (model.workingSettings) add(content, progress(model.settingsStatus));
        else if (!model.settingsStatus.isEmpty()) add(content, text(model.settingsStatus, 14, MUTED));
        add(content, button("API 키 발급 페이지", false, () -> openUrl("openai".equals(d.provider) ? "https://platform.openai.com/api-keys" : "https://aistudio.google.com/apikey")));
        heading(content, "읽기", 22);
        add(content, button("글꼴 · 크기 · 함께 보기", false, this::readingOptions));
        heading(content, "가사 표시", 22);
        String[] codes = getResources().getStringArray(R.array.translation_language_codes);
        String[] labels = getResources().getStringArray(R.array.translation_language_labels);
        String language = d.language; int chosen = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(language)) chosen = i;
        add(content, button("번역·발음 언어: " + labels[chosen], false, () -> new AlertDialog.Builder(this).setTitle("표시 언어")
                .setItems(labels, (dialog,which) -> { d.language = codes[which]; model.notifyUi(); }).show()));

        check("현재곡 제목을 AI로 정리", d.cleanup, value -> d.cleanup = value);
        add(content, text("제목 정리는 API를 사용해요. 정리한 정보는 곡 후보 검색에만 사용하고, 선택 전에는 확정하지 않아요.", 14, MUTED));
        heading(content, "현재 재생 곡", 22);
        add(content, text(hasAccess() ? "알림 접근 권한이 연결되어 있어요" : "현재곡을 가져오려면 알림 접근 권한이 필요해요", 16, MUTED));
        add(content, button("알림 접근 권한 설정", false, this::openAccess));
        heading(content, "주변 음악 인식", 22);
        add(content, button(showAcr ? "ACRCloud 설정 접기" : "ACRCloud 연결 설정", false, () -> { showAcr = !showAcr; render(); }));
        if (showAcr) {
        add(content, text(BuildConfig.ACR_RECOGNITION_ENABLED ? "ACRCloud 연결이 활성화되어 있어요" : "연결 준비만 제공해요. 현재 버전은 마이크 녹음과 인식 요청을 하지 않아요.", 16, MUTED));
        labelInput("ACRCloud Host", "identify-….acrcloud.com", d.acrHost, false, value -> d.acrHost = value);
        labelInput("Access Key", "ACRCloud 프로젝트 키", d.acrKey, true, value -> d.acrKey = value);
        labelInput("Access Secret", "ACRCloud 프로젝트 비밀키", d.acrSecret, true, value -> d.acrSecret = value);
        add(content, button("입력 형식 확인 · 서버 호출 없음", false, () -> {
            String invalid = RecognitionConfig.validate(d.acrHost, d.acrKey, d.acrSecret);
            new AlertDialog.Builder(this).setTitle(invalid.isEmpty() ? "입력 형식이 올바라요" : "입력을 확인해 주세요")
                    .setMessage(invalid.isEmpty() ? "실제 인증·곡 인식은 아직 확인하지 않았어요." : invalid).setPositiveButton("확인",null).show();
        }));
        }
        add(content, button("설정 저장", true, model::saveDraft));
        add(content, button("연결 · 요청 진단", false, () -> new AlertDialog.Builder(this).setTitle("연결 및 요청 진단")
                .setMessage(model.diagnosticSummary()).setPositiveButton("확인", null).show()));
        heading(content, "데이터 관리", 22);
        add(content, text(model.store.stats() + "\n검색 7일 · AI 30일 · 원문 가사 기간 제한 없음\n캐시 최대 20 MB (초과하면 오래된 파일부터 정리)", 14, MUTED));
        if (!model.storageNotice.isEmpty()) add(content, text(model.storageNotice, 14, MUTED));
        add(content, button("데이터 폴더 보기", false, () -> browse(model.store.root())));
        add(content, button("데이터 내보내기", false, this::exportData));
        add(content, button("캐시만 초기화", false, () -> confirmReset(false)));
        add(content, button("기록·보관함 포함 전체 초기화", false, () -> confirmReset(true)));
    }
    private void renderDetail() {
        if (model.selected == null) {
            if (detail != null) { detail.setOnDismissListener(null); rebuilding = true; detail.dismiss(); rebuilding = false; detail = null; }
            return;
        }
        boolean fresh = detail == null || dialogSong == null || !dialogSong.identity().equals(model.selected.identity());
        if (fresh) {
            rebuilding = true; if (detail != null) { detail.setOnDismissListener(null); detail.dismiss(); } rebuilding = false;
            dialogSong = model.selected; bodySignature = tabsSignature = errorSignature = "";
            requestedOutputs = dialogSong.identity().equals(restoredReaderSong) ? restoredRequest : "";
            readerOffsets.clear();
            reader.combined("combined".equals(model.detailTab));

            detail = new Dialog(this); detail.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout shell = column(); GradientDrawable readerBackground = background(SURFACE, 0);
            if ("orange_green".equals(reader.theme())) readerBackground.setStroke(dp(8), BG);
            shell.setBackground(readerBackground);
            shell.setPadding(dp(20), dp(12), dp(20), dp(8));
            LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
            header.addView(new ArtworkView(this, dialogSong, ACCENT, palette.panel), new LinearLayout.LayoutParams(dp(48), dp(48)));
            LinearLayout identity = column(); TextView title = text(dialogSong.getTitle(), 21, TEXT);
            identity.setPadding(dp(12), 0, dp(8), 0);
            title.setTypeface(null, Typeface.BOLD); title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            identity.addView(title); TextView artist = text(dialogSong.getArtist(), 14, MUTED); artist.setMaxLines(1); identity.addView(artist);
            header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
            MaterialButton close = button("닫기", false, model::closeDetail); header.addView(close); shell.addView(header);
            detailTabs = new LinearLayout(this); detailTabs.setBaselineAligned(false);
            shell.addView(detailTabs, new LinearLayout.LayoutParams(-1, -2));
            loadingRow = new LinearLayout(this); loadingRow.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.ProgressBar spinner = spinner(); loadingRow.addView(spinner, new LinearLayout.LayoutParams(dp(24), dp(24)));
            loadingLabel = text("", 13, MUTED); loadingLabel.setPadding(dp(12), 0, dp(8), 0);
            loadingRow.addView(loadingLabel, new LinearLayout.LayoutParams(0, -2, 1));
            cancelGeneration = button("취소", false, () -> { if (model.loadingLyrics) model.cancelLyrics(); else model.cancelAi(); }); loadingRow.addView(cancelGeneration); shell.addView(loadingRow);
            detailStatus = text("", 12, MUTED); detailStatus.setContentDescription("가사 출처와 실제 생성 제공자, 눌러서 자세히 보기"); detailStatus.setMaxLines(2); detailStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detailStatus.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("가사 및 생성 정보").setMessage(sourceStatus()).setPositiveButton("닫기", null).show());
            shell.addView(detailStatus);
            readerErrors = column(); shell.addView(readerErrors);
            View divider = new View(this); divider.setBackgroundColor(palette.line); shell.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
            // Only this weighted region scrolls; header and controls never compete for a vertical gesture.
            lyricsScroll = new ScrollView(this); lyricsScroll.setFillViewport(true); lyricsScroll.setClipToPadding(false);
            lyricsScroll.setContentDescription("가사 읽기 영역");
            lyricsScroll.setPadding(0, dp(20), 0, dp(24));
            detailBody = text("", reader.size(), TEXT); detailBody.setTextIsSelectable(true);
            lyricsScroll.addView(detailBody, new ScrollView.LayoutParams(-1, -2));
            shell.addView(lyricsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout actions = new LinearLayout(this);
            actions.addView(button("읽기 설정", false, this::readingOptions), new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, -2, 1); actionParams.leftMargin = dp(8);
            actions.addView(button("곡 메뉴", false, this::songActions), actionParams); shell.addView(actions);
            final Dialog displayed = detail;
            final String displayedSong = dialogSong.identity();
            detail.setContentView(shell); detail.setOnDismissListener(ignored -> {
                if (!rebuilding && detail == displayed && model.selected != null && displayedSong.equals(model.selected.identity())) model.closeDetail();
            });
            detail.show();
            UiMotion.enter(shell, 16, 0, reader.motion());
            Window window = detail.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(SURFACE));
                window.setLayout(Math.min(dp(760), getResources().getDisplayMetrics().widthPixels), ViewGroup.LayoutParams.MATCH_PARENT);
                ViewCompat.setOnApplyWindowInsetsListener(shell, (v, insets) -> {
                    androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                    v.setPadding(dp(20) + bars.left, dp(8) + bars.top, dp(20) + bars.right, dp(8) + bars.bottom); return insets;
                });
            }
        }
        String original = model.lyrics == null ? "" : model.lyrics.plain;
        String language = original.isEmpty() ? (model.loadingLyrics ? "확인 중" : "미확인") : LyricsLanguage.describe(original);
        boolean aiLyrics = model.lyrics != null && model.lyrics.aiSupplied;
        String tabState = reader.combined() + model.detailTab + language + aiLyrics;
        if (!tabState.equals(tabsSignature)) {
            tabsSignature = tabState; detailTabs.removeAllViews();
            String script = LyricsLanguage.hasJapanese(original) ? "일본어" : LyricsLanguage.hasKorean(original) ? "한국어"
                    : LyricsLanguage.hasHan(original) ? "한자" : LyricsLanguage.isLatinOnly(original) ? "라틴 문자" : language;
            readerTab("combined", "함께 보기"); readerTab("original", (aiLyrics ? "AI 가사\n" : "원본\n") + script);
            readerTab("translation", "번역"); readerTab("pronunciation", "발음");
        }
        boolean busy = model.loadingLyrics || model.loadingAi;
        loadingRow.setVisibility(busy ? View.VISIBLE : View.GONE);
        loadingLabel.setText(model.loadingLyrics ? model.lyricsStatus : model.aiStatus == null || model.aiStatus.isEmpty() ? "AI에 요청 중" : model.aiStatus);
        cancelGeneration.setVisibility(busy ? View.VISIBLE : View.GONE);
        detailStatus.setText(sourceStatus());
        renderReaderErrors();
        String signature = reader.combined() + "|" + model.detailTab + "|" + model.outputs.toString()
                + "|" + reader.original() + reader.translation() + reader.pronunciation() + reader.serif() + reader.size() + reader.spacing();
        if (!signature.equals(bodySignature)) {
            int y = lyricsScroll.getScrollY(); bodySignature = signature;
            detailBody.setTextSize(reader.size()); detailBody.setTypeface(Typeface.create(reader.serif() ? "serif" : "sans-serif", Typeface.NORMAL));
            detailBody.setLineSpacing(dp(reader.spacing() / 3), 1.15f);
            detailBody.setText(reader.combined() ? combinedLyrics(original) : model.outputs.containsKey(model.detailTab) ? model.outputs.get(model.detailTab) : "");
            String mode = reader.combined() ? "combined" : model.detailTab;
            if (!mode.equals(bodyMode)) UiMotion.enter(detailBody, 5, 0, reader.motion());
            bodyMode = mode;
            if (!original.isEmpty() && dialogSong.identity().equals(restoredReaderSong)) { y = restoredReaderY; restoredReaderSong = ""; }
            final int position = y; lyricsScroll.post(() -> lyricsScroll.scrollTo(0, position));
        }
    }
    private void renderReaderErrors() {
    String signature = model.outputErrors.toString() + model.outputs.keySet() + reader.combined() + model.detailTab + reader.translation() + reader.pronunciation() + model.loadingAi + model.loadingLyrics + model.lyricsMissing + (model.lyrics != null);
    if (signature.equals(errorSignature)) return;
    errorSignature = signature; readerErrors.removeAllViews();
    if (!model.loadingLyrics && model.lyrics == null) {
        readerErrors.addView(button("AI에 가사 직접 요청", false, () -> confirmDirectAiLyrics("가사 서버에서 원문을 확보하지 못했어요.")));
        if (model.lyricsMissing) readerErrors.addView(button("AI로 추가 탐색", false, this::confirmAiRecovery));
        else readerErrors.addView(button("가사 다시 불러오기", false, () -> { if (model.selected != null) model.open(model.selected, model.source); }));
        readerErrors.addView(button("가사 직접 붙여넣기", false, this::pasteLyrics));
    }
    if (model.lyrics != null && !model.loadingAi) {
        for (String kind : new String[]{"translation", "pronunciation"}) {
            boolean enabled = reader.combined() ? ("translation".equals(kind) ? reader.translation() : reader.pronunciation()) : kind.equals(model.detailTab);
            if (enabled && !model.outputs.containsKey(kind) && !model.outputErrors.containsKey(kind)) {
                readerErrors.addView(button(("translation".equals(kind) ? "번역" : "발음") + " 만들기 · API 사용", false,
                        () -> model.requestOutputs("translation".equals(kind), "pronunciation".equals(kind))));
            }
        }
    }
    for (String kind : new String[]{"translation", "pronunciation"}) {
        boolean visible = reader.combined() ? ("translation".equals(kind) ? reader.translation() : reader.pronunciation()) : kind.equals(model.detailTab);
        if (!visible || !model.outputErrors.containsKey(kind)) continue;
        String label = "translation".equals(kind) ? "번역" : "발음";
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView message = text(label + " · " + model.outputErrors.get(kind), 13, palette.error);
        message.setMaxLines(2); message.setEllipsize(android.text.TextUtils.TruncateAt.END);
        message.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(label + " 요청 안내").setMessage(model.outputErrors.get(kind)).setPositiveButton("확인", null).show());
        row.addView(message, new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton retry = button("재시도", false, () -> model.retryOutput(kind)); retry.setEnabled(!model.loadingAi);
        row.addView(retry); readerErrors.addView(row);
    }
}
    private CharSequence combinedLyrics(String original) {

        android.text.SpannableStringBuilder result = new android.text.SpannableStringBuilder();
        if (original.isEmpty()) return model.outputs.containsKey("original") ? model.outputs.get("original") : "";
        String[] lines = original.split("\\r?\\n", -1);
        String[] translation = successfulLines("translation"), pronunciation = successfulLines("pronunciation");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) { result.append("\n"); continue; }
            if (reader.original()) appendLine(result, lines[i], TEXT, 1f);
            if (reader.translation() && i < translation.length) appendLine(result, translation[i], reader.original() ? MUTED : TEXT, reader.original() ? .92f : 1f);
            if (reader.pronunciation() && i < pronunciation.length) appendLine(result, pronunciation[i], MUTED, .88f);
            int start = result.length(); result.append("\n");
            result.setSpan(new android.text.style.AbsoluteSizeSpan(reader.spacing(), true), start, result.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return result;
    }
    private String[] successfulLines(String kind) {
        String value = model.outputs.get(kind);
        return value == null || !model.outputSources.containsKey(kind) ? new String[0] : value.split("\\r?\\n", -1);
    }
    private void appendLine(android.text.SpannableStringBuilder target, String line, int color, float scale) {
        int start = target.length(); target.append(line).append("\n");
        target.setSpan(new android.text.style.ForegroundColorSpan(color), start, target.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        target.setSpan(new android.text.style.RelativeSizeSpan(scale), start, target.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    private String sourceStatus() {
        String notice = model.lyrics == null ? "가사 탐색 · " + model.lyricsStatus : "가사 제공 · " + (model.lyrics.source == null ? "LRCLIB" : model.lyrics.source) + " · " + LyricsLanguage.describe(model.lyrics.plain)
                + (model.lyrics.notice == null || model.lyrics.notice.isEmpty() ? "" : "\n" + model.lyrics.notice);
        for (String kind : new String[]{"translation", "pronunciation"}) {
            if ((reader.combined() ? ("translation".equals(kind) ? reader.translation() : reader.pronunciation()) : kind.equals(model.detailTab))
                    && model.outputSources.containsKey(kind)) notice += "\n" + ("translation".equals(kind) ? "번역 · " : "발음 · ") + model.outputSources.get(kind);
        }
        return notice + (!model.loadingAi && "생성을 취소했어요".equals(model.aiStatus) ? "\n" + model.aiStatus : "")
                + (model.storageNotice.isEmpty() ? "" : "\n" + model.storageNotice);
    }
    private void readerTab(String id, String label) {
        boolean selected = "combined".equals(id) ? reader.combined() : !reader.combined() && id.equals(model.detailTab);
        MaterialButton tab = button(label, selected, () -> {
            String oldKey = reader.combined() ? "combined" : model.detailTab;
            readerOffsets.put(oldKey, lyricsScroll.getScrollY());
            reader.combined("combined".equals(id));
            if (!reader.combined()) model.selectTab(id); else { model.detailTab = "combined"; requestedOutputs = ""; renderDetail(); }
            int y = readerOffsets.containsKey(id) ? readerOffsets.get(id) : 0;
            lyricsScroll.post(() -> lyricsScroll.scrollTo(0, y));
        });
        tab.setSelected(selected); tab.setContentDescription(label + (selected ? ", 선택됨" : ""));
        tab.setTextColor(selected ? ACCENT : MUTED); tab.setBackgroundTintList(ColorStateList.valueOf(SURFACE));
        tab.setStrokeWidth(selected ? dp(1) : 0); tab.setStrokeColor(ColorStateList.valueOf(ACCENT)); tab.setCornerRadius(dp(4));
        tab.setTextSize(12); tab.setMinWidth(0); tab.setMinimumWidth(0); tab.setPadding(dp(4), 0, dp(4), 0);
        tab.setMaxLines(2); tab.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1); params.rightMargin = dp(4);
        detailTabs.addView(tab, params);
    }
    private void songActions() {
        new AlertDialog.Builder(this).setTitle(dialogSong.getTitle()).setItems(new String[]{
                model.store.isSaved(model.selected) ? "보관함에서 제거" : "보관함에 저장", "YouTube에서 검색",
                dialogSong.getAppleUrl().isEmpty() ? "Apple Music에서 검색" : "Apple Music에서 곡 열기", "Spotify에서 검색"}, (d, which) -> {
            if (which == 0) {
                model.toggleSaved(); Toast.makeText(this, model.store.isSaved(model.selected) ? "보관함에 담았어요" : "보관함에서 꺼냈어요", Toast.LENGTH_SHORT).show();
            } else serviceLink(which == 1 ? "youtube" : which == 2 ? "apple" : "spotify");
        }).setNegativeButton("닫기", null).show();
    }
    private void readingOptions() {
        LinearLayout options = column(); options.setPadding(dp(20), dp(8), dp(20), dp(16));
        CheckBox combined = readerCheck(options, "함께 보기를 기본으로", reader.combined());
        add(options, text("함께 표시할 항목", 15, TEXT));
        String language = model.lyrics == null ? "가사 언어 자동 확인" : LyricsLanguage.describe(model.lyrics.plain);
        CheckBox original = readerCheck(options, "원본 · " + language, reader.original());
        CheckBox translation = readerCheck(options, "번역", reader.translation());
        CheckBox pronunciation = readerCheck(options, "발음", reader.pronunciation());
        CheckBox serif = readerCheck(options, "명조 글꼴 사용 (기본: 고딕)", reader.serif());
        CheckBox motion = readerCheck(options, "자연스러운 화면 전환과 버튼 반응", reader.motion());
        CheckBox preGenerate = readerCheck(options, "선택한 번역·발음 미리 생성 (API 사용료 발생)", reader.preGenerate());
        add(options, text("기본은 저장된 결과만 준비해요. 새 생성은 탭 또는 만들기 버튼을 누르면 시작해요.", 13, MUTED));
        TextView preview = text("가사를 편안하게 읽어요\n音楽をもっと近くに", reader.size(), TEXT);
        android.widget.SeekBar size = new android.widget.SeekBar(this); size.setMax(14); size.setProgress(reader.size() - 14);
        TextView sizeLabel = text("글자 크기 · " + reader.size(), 14, MUTED); add(options, sizeLabel); add(options, size);
        android.widget.SeekBar spacing = new android.widget.SeekBar(this); spacing.setMax(16); spacing.setProgress(reader.spacing() - 4);
        TextView gapLabel = text("줄 묶음 간격 · " + reader.spacing(), 14, MUTED); add(options, gapLabel); add(options, spacing); add(options, preview);
        Runnable updatePreview = () -> {
            preview.setTextSize(14 + size.getProgress()); preview.setTypeface(Typeface.create(serif.isChecked() ? "serif" : "sans-serif", Typeface.NORMAL));
            preview.setLineSpacing(dp(4 + spacing.getProgress()), 1.15f);
            sizeLabel.setText("글자 크기 · " + (14 + size.getProgress())); gapLabel.setText("줄 묶음 간격 · " + (4 + spacing.getProgress()));
        };
        android.widget.SeekBar.OnSeekBarChangeListener listener = new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar bar, int value, boolean user) { updatePreview.run(); }
            public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            public void onStopTrackingTouch(android.widget.SeekBar bar) { }
        };
        size.setContentDescription("가사 글자 크기, 14에서 28"); spacing.setContentDescription("줄 묶음 간격, 4에서 20");
        size.setOnSeekBarChangeListener(listener); spacing.setOnSeekBarChangeListener(listener);
        serif.setOnCheckedChangeListener((v, checked) -> updatePreview.run()); updatePreview.run();
        ScrollView optionsScroll = new ScrollView(this); optionsScroll.addView(options);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("읽기 설정").setView(optionsScroll).setNegativeButton("취소", null).setPositiveButton("적용", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!original.isChecked() && !translation.isChecked() && !pronunciation.isChecked()) {
                Toast.makeText(this, "함께 볼 항목을 하나 이상 선택해 주세요", Toast.LENGTH_SHORT).show(); return;
            }
            reader.save(original.isChecked(), translation.isChecked(), pronunciation.isChecked(), serif.isChecked(), 14 + size.getProgress(), 4 + spacing.getProgress());
            reader.combined(combined.isChecked()); requestedOutputs = ""; bodySignature = "";
            reader.motion(motion.isChecked());
            reader.preGenerate(preGenerate.isChecked());
            if (reader.combined()) model.detailTab = "combined";
            else if ("combined".equals(model.detailTab)) model.detailTab = "original";
            dialog.dismiss();
            if (preGenerate.isChecked() && model.lyrics != null) model.requestOutputs(reader.translation(), reader.pronunciation());
            if (model.selected != null) renderDetail();
        })); dialog.show();
    }
    private void selectTheme(String id) {
        if (reader.theme().equals(id)) return;
        reader.theme(id);
        recreate();
    }
    private CheckBox readerCheck(LinearLayout parent, String label, boolean checked) {
        CheckBox box = new CheckBox(this); box.setText(label); box.setTextSize(15); box.setTextColor(TEXT); box.setMinHeight(dp(48)); box.setChecked(checked); parent.addView(box); return box;
    }
    private android.widget.ProgressBar spinner() {
        android.widget.ProgressBar progress = new android.widget.ProgressBar(this); progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(TEXT)); progress.setContentDescription("작업 진행 중"); return progress;
    }
    private View progress(String message) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(spinner(), new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = text(message, 14, MUTED); label.setPadding(dp(12), dp(8), 0, dp(8)); row.addView(label); return row;
    }
    private void serviceLink(String service) {
        SongInfo song = model.selected; if (song == null) return;
        String query = Uri.encode(song.getTitle() + " " + song.getArtist());
        if ("youtube".equals(service)) openService("https://www.youtube.com/results?search_query=" + query, "com.google.android.youtube");
        else if ("spotify".equals(service)) openService("https://open.spotify.com/search/" + query, "com.spotify.music");
        else {
            String exact = song.getAppleUrl(); Uri uri = Uri.parse(exact);
            openService("https".equals(uri.getScheme()) && "music.apple.com".equals(uri.getHost()) ? exact : "https://music.apple.com/kr/search?term=" + query, "com.apple.android.music");
        }
    }
    private void openService(String url, String packageName) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(packageName)); }
        catch (android.content.ActivityNotFoundException e) { openUrl(url); }
    }
    private void showFailure(AiFailure failure) {
        if (errorDialog != null && errorDialog.isShowing()) return;
        if ("lyrics".equals(model.failureOperation) && !model.aiRecoveryAttempted && model.selected != null && model.lyrics == null) {
            confirmDirectAiLyrics(failure.message); return;
        }
        boolean lyricsServiceFailure = "lyrics".equals(model.failureOperation) && failure.code.startsWith("SERVICE_");
        String message = failure.message;
        if (lyricsServiceFailure) message += "\n\n곡 검색과 가사는 다른 서버를 사용해요. 직접 입력하거나 화면의 AI 직접 요청 버튼을 사용할 수 있어요. AI는 동의 없이 호출하지 않아요.";
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(failure.title).setMessage(message).setNegativeButton("닫기", null);
        if (lyricsServiceFailure && model.selected != null) builder.setNeutralButton("직접 입력", (d,w) -> pasteLyrics());
        if (failure.action == AiFailure.Action.SETTINGS) builder.setPositiveButton("설정 열기", (d,w) -> { model.closeDetail(); model.navigate("settings"); });
        else if (failure.action == AiFailure.Action.BILLING) builder.setPositiveButton("사용량·결제 확인", (d,w) -> openUrl("openai".equals(failure.provider) ? "https://platform.openai.com/settings/organization/billing/overview" : "https://aistudio.google.com/usage"));
        else if (failure.action == AiFailure.Action.RETRY) builder.setPositiveButton("다시 시도", (d,w) -> model.retry());
        errorDialog = builder.show();
    }
    private void confirmDirectAiLyrics(String reason) {
        if (model.selected == null || model.loadingLyrics || model.lyrics != null || (errorDialog != null && errorDialog.isShowing())) return;
        final String identity = model.selected.identity();
        errorDialog = new AlertDialog.Builder(this).setTitle("AI에 가사를 직접 요청할까요?")
                .setMessage(reason + "\n\n" + AppSettings.providerLabel(model.settings.getAiProvider())
                        + "에 곡명·가수·앨범을 보내 가사를 요청해요. LRCLIB 재조회나 웹 검색이 아니에요.\n\n"
                        + "추가 토큰·API 사용료가 발생할 수 있고, 실패해도 과금될 수 있어요. 저작권·콘텐츠 정책으로 거절되거나 부정확할 수 있어요. 결과는 ‘AI 응답 · 미검증’으로 표시하며 싱크는 제공하지 않아요.\n\n번역·발음은 별도로 선택할 때 실행해요.")
                .setNegativeButton("취소", null)
                .setNeutralButton("다시 시도", (d,w) -> { if (model.selected != null && identity.equals(model.selected.identity())) model.open(model.selected, model.source); })
                .setPositiveButton("확인 · AI 요청", (d,w) -> {
                    if (model.selected != null && identity.equals(model.selected.identity())) model.requestAiLyricsWithConsent();
                }).show();
    }
    private void confirmAiRecovery() {
        if (model.selected == null || model.loadingLyrics || (errorDialog != null && errorDialog.isShowing())) return;
        final String identity = model.selected.identity();
        errorDialog = new AlertDialog.Builder(this).setTitle("AI로 가사를 추가 탐색할까요?")
                .setMessage("LRCLIB에서 일치하는 가사를 찾지 못했어요. 두 번째 정식 가사 제공처는 아직 연결되지 않았어요.\n\n"
                        + AppSettings.providerLabel(model.settings.getAiProvider()) + "로 곡명·가수·원제 후보를 보정한 뒤 실제 등록된 가사를 다시 찾아요. "
                        + "웹 전체 검색이나 가사 창작은 하지 않으며, 찾지 못할 수도 있어요. API 사용료가 발생할 수 있어요.\n\n번역·발음은 별도로 선택할 때 생성해요.")
                .setNegativeButton("취소", null).setNeutralButton("직접 입력", (d,w) -> pasteLyrics())
                .setPositiveButton("AI로 찾아보기", (d,w) -> {
                    if (model.selected != null && identity.equals(model.selected.identity())) model.recoverLyricsWithConsent();
                }).show();
    }
    private void pasteLyrics() {
        if (model.selected == null) return;
        final String identity = model.selected.identity();
        EditText input = new EditText(this); input.setHint("사용할 권한이 있는 원문 가사를 붙여넣으세요");
        input.setMinLines(5); input.setMaxLines(12); input.setGravity(Gravity.TOP);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60000)});
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("원문 가사 입력").setView(input)
                .setNegativeButton("취소", null).setPositiveButton("원문으로 사용", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (input.getText().toString().trim().isEmpty()) { input.setError("가사를 입력해 주세요"); return; }
            if (model.selected != null && identity.equals(model.selected.identity())) model.importLyrics(input.getText().toString());
            dialog.dismiss();
        })); dialog.show();
    }
    private boolean hasAccess() {
        return androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
    }
    private void openAccess() { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException e) { Toast.makeText(this, "링크를 열 브라우저가 없어요", Toast.LENGTH_LONG).show(); }
    }
    private void confirmReset(boolean all) {
        new AlertDialog.Builder(this).setTitle(all ? "전체 데이터를 지울까요?" : "캐시를 지울까요?")
                .setMessage(all ? "가사·번역과 최근 기록·보관한 곡·추천이 삭제돼요. API 키는 유지돼요." : "가사·번역·검색·추천 캐시를 지워요. 최근 기록과 보관한 곡은 유지돼요.")
                .setNegativeButton("취소",null).setPositiveButton("초기화", (d,w) -> model.clear(all)).show();
    }
    private void browse(File directory) {
        File[] files = directory.listFiles(); if (files == null) files = new File[0];
        final File[] entries = files;
        String[] names = new String[files.length]; for (int i=0;i<files.length;i++) names[i] = files[i].getName() + (files[i].isDirectory() ? "/" : " · " + files[i].length() + " B");
        new AlertDialog.Builder(this).setTitle(directory.getName()).setItems(names, (d,which) -> {
            File file = entries[which]; if (file.isDirectory()) browse(file);
            else {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
                    StringBuilder data = new StringBuilder(); char[] chunk = new char[2048]; int n;
                    while ((n = reader.read(chunk)) > 0 && data.length() < 100000) data.append(chunk,0,n);
                    TextView body = text(data.toString(), 14, TEXT); body.setTextIsSelectable(true); body.setPadding(dp(16),dp(16),dp(16),dp(16));
                    ScrollView view = new ScrollView(this); view.addView(body);
                    new AlertDialog.Builder(this).setTitle(file.getName()).setView(view).setPositiveButton("닫기",null).show();
                } catch (Exception e) { Toast.makeText(this, "파일을 읽지 못했어요. 누락된 데이터는 다시 불러와요.", Toast.LENGTH_LONG).show(); }
            }
        }).setNegativeButton("닫기",null).setNeutralButton("경로 복사", (d,w) -> {
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("데이터 경로",directory.getAbsolutePath()));
        }).show();
    }
    private void exportData() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "lyrics-data.zip");
        try { startActivityForResult(intent, 62); } catch (android.content.ActivityNotFoundException e) { Toast.makeText(this,"파일 저장 앱을 사용할 수 없어요",Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request == 62 && result == RESULT_OK && data != null && data.getData() != null) {
            Uri destination = data.getData(); File folder = model.store.root(); android.content.ContentResolver resolver = getApplicationContext().getContentResolver();
            new Thread(() -> {
                boolean ok = true;
                try (java.io.OutputStream stream = resolver.openOutputStream(destination); ZipOutputStream zip = new ZipOutputStream(stream)) { zipFolder(folder, folder, zip); }
                catch (Exception e) { ok = false; }
                boolean done = ok; runOnUiThread(() -> { if (!isDestroyed()) Toast.makeText(this, done ? "데이터를 내보냈어요" : "내보내기에 실패했어요. 저장 공간과 파일 권한을 확인해 주세요.", Toast.LENGTH_LONG).show(); });
            }, "lyrics-export").start();
        }
    }
    private static void zipFolder(File root, File directory, ZipOutputStream zip) throws java.io.IOException {
        File[] files = directory.listFiles(); if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) zipFolder(root,file,zip);
            else if (file.getName().endsWith(".json")) {
                zip.putNextEntry(new ZipEntry(root.toURI().relativize(file.toURI()).getPath()));
                try (FileInputStream in = new FileInputStream(file)) { byte[] buffer = new byte[8192]; int n; while ((n=in.read(buffer))>0) zip.write(buffer,0,n); }
                zip.closeEntry();
            }
        }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 61) {
            if (grants.length>0 && grants[0] == PackageManager.PERMISSION_GRANTED) model.recognize();
            else new AlertDialog.Builder(this).setTitle("마이크 권한이 필요해요").setMessage("주변 음악 인식에는 마이크 권한이 필요해요. 검색과 현재곡 가져오기는 계속 사용할 수 있어요.")
                    .setPositiveButton("앱 권한 설정",(d,w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNegativeButton("닫기",null).show();
        }
    }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private TextView text(String value,int size,int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setFontFeatureSettings("kern"); view.setLineSpacing(dp(2), 1.08f); return view; }
    private void heading(LinearLayout parent,String value,int size) { TextView view = text(value,size,TEXT); view.setTypeface(view.getTypeface(), Typeface.BOLD); LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1,-2); layout.topMargin = dp(parent.getChildCount() == 0 ? 4 : 28); layout.bottomMargin = dp(4); parent.addView(view,layout); }
    private void add(LinearLayout parent,View view) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2); params.topMargin=dp(12); parent.addView(view,params); }
    private MaterialButton button(String label,boolean primary,Runnable action) {
        MaterialButton button = new MaterialButton(this); button.setText(label); button.setTextSize(15); button.setAllCaps(false);
        button.setLetterSpacing(0); button.setElevation(0); button.setStateListAnimator(null);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setCornerRadius(dp(8));
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : palette.panel)); button.setTextColor(primary ? palette.onAccent : TEXT);
        button.setStrokeWidth(primary ? 0 : dp(1)); button.setStrokeColor(ColorStateList.valueOf(palette.line));
        UiMotion.press(button, reader.motion());
        button.setOnClickListener(v -> { v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); action.run(); }); return button;
    }
    private GradientDrawable background(int color,int radius) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg; }
    private interface StringChange { void changed(String value); }
    private interface BooleanChange { void changed(boolean value); }
    private EditText input(String hint,String value,boolean secret,StringChange change) {
        EditText field = new EditText(this); field.setTextSize(16); field.setTextColor(TEXT); field.setHintTextColor(MUTED); field.setHint(hint);
        field.setInputType(secret ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD : android.text.InputType.TYPE_CLASS_TEXT);
        field.setMinHeight(dp(52)); GradientDrawable border = background(palette.panel,8); border.setStroke(dp(1), palette.line); field.setBackground(border); field.setPadding(dp(12),dp(12),dp(12),dp(12));
        field.setText(value);
        field.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s,int st,int c,int a) {} public void onTextChanged(CharSequence s,int st,int b,int c) { change.changed(s.toString()); } public void afterTextChanged(Editable e) {} });
        return field;
    }
    private void labelInput(String label,String hint,String value,boolean secret,StringChange change) {
        TextView title = text(label,15,TEXT); add(content,title); EditText field = input(hint,value,secret,change); field.setContentDescription(label); add(content,field);
        title.setLabelFor(View.generateViewId()); field.setId(title.getLabelFor());
    }
    private void check(String label,boolean selected,BooleanChange change) { CheckBox check = new CheckBox(this); check.setText(label); check.setTextSize(16); check.setTextColor(TEXT); check.setMinHeight(dp(48)); check.setChecked(selected); check.setOnCheckedChangeListener((v,b) -> change.changed(b)); add(content,check); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
