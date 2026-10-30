package com.codex.aitimeline;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Dialog;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_SCREEN_CAPTURE = 4401;
    private static final String RANGE_DAY = "day";
    private static final String RANGE_WEEK = "week";
    private static final String RANGE_MONTH = "month";
    private static final String RANGE_YEAR = "year";
    private static final String TEXT_PRIMARY = "#191F28";
    private static final String TEXT_SECONDARY = "#6B7684";
    private static final String TEXT_TERTIARY = "#8B95A1";
    private static final String BRAND_BLUE = "#3182F6";
    private static final String LOSS_RED = "#E05F5F";
    private static final String RECOVERY_TEAL = "#16A085";

    private DrawerLayout drawerLayout;
    private TextView accountText;
    private TextView statusText;
    private TextView permissionsText;
    private TextView accessibilityHelpText;
    private TextView liveSummaryText;
    private TextView liveMetaText;
    private LinearLayout timelineContainer;
    private Button googleButton;
    private Button liveModeButton;
    private Button gridModeButton;
    private Button focusModeButton;
    private Button yearRangeButton;

    private TimelineStore timelineStore;
    private AppSettings appSettings;
    private GeminiTimelineClient geminiClient;
    private GoogleSignInClient googleSignInClient;
    private ActivityResultLauncher<Intent> signInLauncher;
    private ActivityResultLauncher<String> notificationPermissionLauncher;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler liveHandler = new Handler(Looper.getMainLooper());
    private String selectedRange = RANGE_DAY;
    private long lastSilentUsageImportAt = 0L;

    private static class AppUsageSummary {
        String packageName = "";
        String appName = "";
        String category = "";
        int minutes = 0;
        final List<TimelineEvent> events = new ArrayList<>();
    }

    private static class TimeBucket {
        long start;
        long end;
        String title = "";
        String subtitle = "";
        final List<TimelineEvent> events = new ArrayList<>();
    }

    private final BroadcastReceiver accessibilityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            timelineStore.addEvent(new TimelineEvent(
                    System.currentTimeMillis(),
                    "Accessibility",
                    getPackageName(),
                    intent.getBooleanExtra(ActivityAccessibilityService.EXTRA_CONNECTED, false)
                            ? "화면 컨텍스트 서비스 연결됨"
                            : "화면 컨텍스트 서비스 해제됨",
                    "접근성 서비스 상태가 변경되었습니다."
            ));
            refreshUi();
        }
    };
    private final Runnable liveRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            importUsageSnapshot(false);
            refreshUi();
            liveHandler.postDelayed(this, 8000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        timelineStore = new TimelineStore(this);
        appSettings = new AppSettings(this);
        geminiClient = new GeminiTimelineClient();
        drawerLayout = findViewById(R.id.drawer_layout);
        accountText = findViewById(R.id.text_account);
        statusText = findViewById(R.id.text_status);
        permissionsText = findViewById(R.id.text_permissions);
        accessibilityHelpText = findViewById(R.id.text_accessibility_help);
        liveSummaryText = findViewById(R.id.text_live_summary);
        liveMetaText = findViewById(R.id.text_live_meta);
        timelineContainer = findViewById(R.id.layout_timeline);
        googleButton = findViewById(R.id.button_google);
        gridModeButton = findViewById(R.id.button_range_day);
        focusModeButton = findViewById(R.id.button_range_week);
        liveModeButton = findViewById(R.id.button_range_month);
        yearRangeButton = findViewById(R.id.button_range_year);

        GoogleSignInOptions signInOptions = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, signInOptions);
        signInLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(result.getData());
            handleSignInResult(task);
        });
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    showToast(isGranted ? "알림 권한이 켜졌습니다." : "알림 권한이 꺼져 있습니다.");
                    refreshUi();
                }
        );

        findViewById(R.id.button_menu).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        googleButton.setOnClickListener(v -> openAccountSettingsDialog());
        findViewById(R.id.button_ai_settings).setOnClickListener(v -> openAiSettingsDialog());
        findViewById(R.id.button_permission_setup).setOnClickListener(v -> openPermissionSettingsDialog());
        findViewById(R.id.button_refresh).setOnClickListener(v -> openDataSettingsDialog());
        findViewById(R.id.button_clear).setOnClickListener(v -> confirmClearTimeline());
        findViewById(R.id.button_ai_summary).setOnClickListener(v -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            summarizeTimelineWithGemini();
        });
        gridModeButton.setOnClickListener(v -> switchRange(RANGE_DAY));
        focusModeButton.setOnClickListener(v -> switchRange(RANGE_WEEK));
        liveModeButton.setOnClickListener(v -> switchRange(RANGE_MONTH));
        yearRangeButton.setOnClickListener(v -> switchRange(RANGE_YEAR));

        seedWelcomeEventIfNeeded();
        importUsageSnapshot(false);
        refreshUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        liveHandler.removeCallbacks(liveRefreshRunnable);
        try {
            unregisterReceiver(accessibilityReceiver);
        } catch (Exception ignored) {
        }
        executor.shutdownNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerAccessibilityReceiver();
        importUsageSnapshot(false);
        refreshUi();
        liveHandler.removeCallbacks(liveRefreshRunnable);
        liveHandler.postDelayed(liveRefreshRunnable, 8000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        liveHandler.removeCallbacks(liveRefreshRunnable);
    }

    private void registerAccessibilityReceiver() {
        try {
            IntentFilter filter = new IntentFilter(ActivityAccessibilityService.ACTION_STATUS_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(accessibilityReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(accessibilityReceiver, filter);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                showToast("화면 캡처 세션 동의가 완료되었습니다.");
            } else {
                showToast("화면 캡처 세션이 취소되었습니다.");
            }
            refreshUi();
        }
    }

    private void handleGoogleButton() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            signInLauncher.launch(googleSignInClient.getSignInIntent());
        } else {
            googleSignInClient.signOut().addOnCompleteListener(task -> {
                refreshUi();
            });
        }
    }

    private void handleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);
            showToast(account.getEmail() + " 계정이 연결되었습니다.");
        } catch (ApiException e) {
            showToast("Google 로그인에 실패했습니다: " + e.getStatusCode());
        }
        refreshUi();
    }

    private void requestScreenCaptureConsent() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            showToast("이 기기에서 화면 캡처 세션을 시작할 수 없습니다.");
            return;
        }
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
    }

    private void continuePermissionSetup() {
        if (!hasUsageAccess()) {
            showPermissionReason(
                    "앱 사용 기록 권한",
                    "타임라인의 기본 재료입니다. 어떤 앱을 언제 얼마나 사용했는지 가져옵니다.",
                    this::openUsageAccessSettings
            );
            return;
        }
        if (!isAccessibilityEnabled()) {
            showPermissionReason(
                    "화면 컨텍스트 권한",
                    "앱 이름만으로 부족한 부분을 보강합니다. 접근성 화면에서 '제한된 설정으로 제어됨'이 뜨면 먼저 앱 정보에서 제한된 설정을 허용해야 합니다.",
                    this::openAccessibilitySettings
            );
            return;
        }
        if (!hasNotificationPermission()) {
            showPermissionReason(
                    "알림 권한",
                    "나중에 백그라운드 기록 상태를 항상 보이게 하기 위해 필요합니다.",
                    this::requestNotificationPermission
            );
            return;
        }
        showPermissionReason(
                "화면 캡처 세션",
                "선택 기능입니다. 사용자가 직접 동의한 세션에서만 화면 분석 토큰을 받을 수 있습니다.",
                this::requestScreenCaptureConsent
        );
    }

    private void openPermissionSettingsDialog() {
        Dialog dialog = buildSheetDialog();
        LinearLayout shell = buildDialogShell();
        dialog.setContentView(shell);

        shell.addView(buildText("권한 설정", TEXT_PRIMARY, 22, true));
        shell.addView(buildText("기록에 필요한 권한을 단계별로 켭니다.", TEXT_SECONDARY, 13, false));
        shell.addView(statusLine("앱 사용 시간", hasUsageAccess()));
        shell.addView(statusLine("화면 흐름 기록", isAccessibilityEnabled()));
        shell.addView(statusLine("알림", hasNotificationPermission()));
        shell.addView(statusLine("기록 일시정지", appSettings.isTrackingPaused()));

        shell.addView(dialogButton(nextPermissionLabel(), true, v -> continuePermissionSetup()));
        shell.addView(dialogButton("앱 사용 시간 설정", false, v -> openUsageAccessSettings()));
        shell.addView(dialogButton("화면 흐름 설정", false, v -> openAccessibilitySettings()));
        if (!isAccessibilityEnabled()) {
            shell.addView(dialogButton("제한된 설정 해제 안내", false, v -> showRestrictedSettingsHelp()));
        }
        shell.addView(dialogButton("알림 설정", false, v -> requestNotificationPermission()));
        shell.addView(dialogButton(appSettings.isTrackingPaused() ? "기록 다시 시작" : "기록 일시정지", false, v -> {
            appSettings.setTrackingPaused(!appSettings.isTrackingPaused());
            showToast(appSettings.isTrackingPaused() ? "기록을 일시정지했습니다." : "기록을 다시 시작했습니다.");
            dialog.dismiss();
            refreshUi();
        }));
        shell.addView(dialogButton("고급: 화면 캡처 세션", false, v -> requestScreenCaptureConsent()));
        shell.addView(dialogButton("닫기", false, v -> dialog.dismiss()));
        showSheetDialog(dialog);
    }

    private void openAccountSettingsDialog() {
        Dialog dialog = buildSheetDialog();
        LinearLayout shell = buildDialogShell();
        dialog.setContentView(shell);

        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        shell.addView(buildText("계정", TEXT_PRIMARY, 22, true));
        shell.addView(buildText(account == null
                ? "지금은 로컬 저장만 사용합니다. 데스크탑 연동을 붙일 때 계정이 필요합니다."
                : account.getEmail(), TEXT_SECONDARY, 13, false));
        shell.addView(dialogButton(account == null ? "Google 계정 연결" : "계정 연결 해제", true, v -> {
            dialog.dismiss();
            handleGoogleButton();
        }));
        shell.addView(dialogButton("닫기", false, v -> dialog.dismiss()));
        showSheetDialog(dialog);
    }

    private void openDataSettingsDialog() {
        Dialog dialog = buildSheetDialog();
        LinearLayout shell = buildDialogShell();
        dialog.setContentView(shell);

        shell.addView(buildText("기간/데이터 관리", TEXT_PRIMARY, 22, true));
        shell.addView(buildText("원본 기록은 여기서 확인하고, 메인은 앱별 사용시간과 행동 요약에 집중합니다.", TEXT_SECONDARY, 13, false));
        shell.addView(dialogButton("지금 동기화", true, v -> {
            importUsageSnapshot(true);
            dialog.dismiss();
            drawerLayout.closeDrawer(GravityCompat.START);
            refreshUi();
        }));
        shell.addView(dialogButton("원본 기록 보기", false, v -> {
            dialog.dismiss();
            drawerLayout.closeDrawer(GravityCompat.START);
            showRawRecordsDialog(mainTimelineEvents(selectedRangeEvents(timelineStore.loadEvents())));
        }));
        shell.addView(dialogButton("수동 활동 추가", false, v -> openManualActivityDialog()));
        shell.addView(dialogButton("회수 목표 설정", false, v -> openGoalDialog()));
        shell.addView(dialogButton("오늘 메모", false, v -> openMemoDialog()));
        shell.addView(dialogButton("숨긴 앱 초기화", false, v -> {
            appSettings.clearHiddenPackages();
            showToast("숨긴 앱 목록을 비웠습니다.");
            dialog.dismiss();
            refreshUi();
        }));
        shell.addView(dialogButton("모든 기록 삭제", false, v -> {
            dialog.dismiss();
            confirmClearTimeline();
        }));
        shell.addView(dialogButton("닫기", false, v -> dialog.dismiss()));
        showSheetDialog(dialog);
    }

    private void openManualActivityDialog() {
        Dialog dialog = buildSheetDialog();
        LinearLayout shell = buildDialogShell();
        dialog.setContentView(shell);
        shell.addView(buildText("수동 활동 추가", TEXT_PRIMARY, 22, true));

        EditText titleInput = dialogInput("활동 이름 예: 운동, 공부, 회의");
        EditText minutesInput = dialogInput("시간(분) 예: 60");
        shell.addView(titleInput);
        shell.addView(minutesInput);
        shell.addView(dialogButton("추가", true, v -> {
            String title = titleInput.getText().toString().trim();
            int minutes = parseInt(minutesInput.getText().toString(), 30);
            if (title.isEmpty()) {
                showToast("활동 이름을 입력해 주세요.");
                return;
            }
            long eventAt = Math.max(
                    startOfCurrentDayMillis(),
                    System.currentTimeMillis() - Math.max(1, minutes) * 60000L
            );
            timelineStore.addEvent(new TimelineEvent(
                    eventAt,
                    "Manual",
                    "manual.activity",
                    title,
                    Math.max(1, minutes) + "분 · 직접 추가"
            ));
            dialog.dismiss();
            refreshUi();
        }));
        shell.addView(dialogButton("닫기", false, v -> dialog.dismiss()));
        showSheetDialog(dialog);
    }

    private void openGoalDialog() {
        Dialog dialog = buildSheetDialog();
        LinearLayout shell = buildDialogShell();
        dialog.setContentView(shell);
        shell.addView(buildText("오늘 회수 목표", TEXT_PRIMARY, 22, true));

        EditText labelInput = dialogInput("목표 이름 예: 회수 목표");
        EditText minutesInput = dialogInput("기준 시간(분) 예: 60");
        labelInput.setText(appSettings.getGoalLabel());
        minutesInput.setText(String.valueOf(appSettings.getGoalMinutes()));
        shell.addView(labelInput);
        shell.addView(minutesInput);
        shell.addView(dialogButton("저장", true, v -> {
            appSettings.saveGoal(labelInput.getText().toString(), parseInt(minutesInput.getText().toString(), 120));
            dialog.dismiss();
            refreshUi();
        }));
        shell.addView(dialogButton("닫기", false, v -> dialog.dismiss()));
        showSheetDialog(dialog);
    }

    private void openMemoDialog() {
        Dialog dialog = buildSheetDialog();
        LinearLayout shell = buildDialogShell();
        dialog.setContentView(shell);
        shell.addView(buildText("오늘 메모", TEXT_PRIMARY, 22, true));
        EditText memoInput = dialogInput("오늘 흐름에 대한 메모");
        memoInput.setMinLines(3);
        memoInput.setText(appSettings.getTodayMemo());
        shell.addView(memoInput);
        shell.addView(dialogButton("저장", true, v -> {
            appSettings.saveTodayMemo(memoInput.getText().toString());
            dialog.dismiss();
            refreshUi();
        }));
        shell.addView(dialogButton("닫기", false, v -> dialog.dismiss()));
        showSheetDialog(dialog);
    }

    private Dialog buildSheetDialog() {
        Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return dialog;
    }

    private LinearLayout buildDialogShell() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(20), dp(18), dp(20), dp(20));
        shell.setBackgroundResource(R.drawable.bg_panel);
        return shell;
    }

    private void showSheetDialog(Dialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private Button dialogButton(String text, boolean primary, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.parseColor(primary ? "#FFFFFF" : TEXT_PRIMARY));
        button.setTextSize(14);
        button.setBackgroundResource(primary ? R.drawable.bg_primary_button : R.drawable.bg_secondary_button);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        params.setMargins(0, dp(10), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private EditText dialogInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(Color.parseColor(TEXT_PRIMARY));
        input.setHintTextColor(Color.parseColor(TEXT_TERTIARY));
        input.setTextSize(14);
        input.setSingleLine(false);
        input.setBackgroundResource(R.drawable.bg_panel_soft);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(12), 0, 0);
        input.setLayoutParams(params);
        return input;
    }

    private TextView statusLine(String label, boolean enabled) {
        return buildText(label + ": " + (enabled ? "켜짐" : "꺼짐"), enabled ? BRAND_BLUE : TEXT_SECONDARY, 14, enabled);
    }

    private void showPermissionReason(String title, String message, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("열기", (dialog, which) -> action.run())
                .setNegativeButton("나중에", null)
                .show();
    }

    private void openUsageAccessSettings() {
        showToast("목록에서 AI Timeline을 찾아 앱 사용 기록을 허용해 주세요.");
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openAccessibilitySettings() {
        showToast("접근성 목록에서 AI Timeline screen observer를 켜 주세요.");
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void showRestrictedSettingsHelp() {
        new AlertDialog.Builder(this)
                .setTitle("제한된 설정 해제")
                .setMessage("디버그 APK처럼 스토어 밖에서 설치한 앱은 Android가 접근성 권한을 막을 수 있습니다.\n\n1. 다음 화면에서 AI Timeline 앱 정보를 엽니다.\n2. 오른쪽 위 점 3개 메뉴를 누릅니다.\n3. '제한된 설정 허용'을 선택합니다.\n4. 다시 접근성 설정에서 AI Timeline screen observer를 켭니다.")
                .setPositiveButton("앱 정보 열기", (dialog, which) -> openAppDetailsSettings())
                .setNegativeButton("접근성 열기", (dialog, which) -> openAccessibilitySettings())
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openAppNotificationSettings();
            return;
        }
        if (hasNotificationPermission()) {
            showToast("알림 권한은 이미 켜져 있습니다.");
            openAppNotificationSettings();
            return;
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void openAppNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            openAppDetailsSettings();
        }
    }

    private void openAppDetailsSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + getPackageName())));
    }

    private void addAccessibilityTestEvent() {
        boolean enabled = isAccessibilityEnabled();
        refreshUi();
        showToast(enabled ? "권한은 켜져 있습니다. 다른 앱을 잠깐 사용해 보세요." : "화면 컨텍스트 권한이 꺼져 있습니다.");
    }

    private void seedWelcomeEventIfNeeded() {
        // The main feed is reserved for the user's activity, not setup logs.
    }

    private void importUsageSnapshot(boolean showFeedback) {
        importUsageSnapshotForRange(showFeedback ? selectedRange : RANGE_DAY, showFeedback, false);
    }

    private void importUsageSnapshotForRange(String range, boolean showFeedback, boolean force) {
        if (appSettings.isTrackingPaused()) {
            if (showFeedback) {
                showToast("기록이 일시정지되어 있습니다.");
            }
            return;
        }
        if (!hasUsageAccess()) {
            if (showFeedback) {
                showToast("앱 사용 기록 권한을 먼저 켜 주세요.");
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && !showFeedback && now - lastSilentUsageImportAt < 7000L) {
            return;
        }
        lastSilentUsageImportAt = now;
        UsageStatsManager manager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            if (showFeedback) {
                showToast("앱 사용 기록을 읽을 수 없습니다.");
            }
            return;
        }

        long start = rangeStartMillis(range);
        UsageEvents events = manager.queryEvents(start, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String currentPackage = "";
        long currentStart = 0L;
        int added = 0;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentPackage = event.getPackageName();
                currentStart = event.getTimeStamp();
            } else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND
                    && currentPackage.equals(event.getPackageName())
                    && currentStart > 0L) {
                long durationMs = Math.max(0L, event.getTimeStamp() - currentStart);
                if (durationMs >= 30000L) {
                    long eventStart = Math.max(currentStart, start);
                    timelineStore.addEvent(PrivacyGuard.sanitize(new TimelineEvent(
                            eventStart,
                            "UsageStats",
                            currentPackage,
                            AppNameResolver.friendlyName(this, currentPackage, currentPackage),
                            formatDuration(durationMs) + " 사용 · " + AppNameResolver.category(currentPackage)
                    )));
                    added++;
                }
                currentPackage = "";
                currentStart = 0L;
            }
        }

        if (showFeedback) {
            showToast(added == 0 ? rangeLabel(range) + " 활동 중 새로 읽을 앱 사용 블록이 없습니다." : rangeLabel(range) + " 앱 사용 블록 " + added + "개를 읽었습니다.");
        }
    }

    private void refreshUi() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account == null) {
            accountText.setText("기기 연동을 켤 때만 계정이 필요합니다.");
            googleButton.setText("계정 연결");
        } else {
            accountText.setText(account.getEmail());
            googleButton.setText("계정 연결 해제");
        }

        boolean usage = hasUsageAccess();
        boolean accessibility = isAccessibilityEnabled();
        List<TimelineEvent> allEvents = timelineStore.loadEvents();
        List<TimelineEvent> rangeEvents = selectedRangeEvents(allEvents);
        statusText.setText(accessibility
                ? (appSettings.isTrackingPaused() ? "일시정지됨" : "기록 중 · " + formatTime(System.currentTimeMillis()) + " 갱신")
                : usage ? "앱 사용 시간 기록 중" : "설정 필요");
        liveSummaryText.setText(buildMainBannerTitle(usage, accessibility, rangeEvents));
        liveMetaText.setText(buildMainBannerMeta(usage, accessibility, rangeEvents));
        permissionsText.setText(
                "앱 사용 시간: " + onOff(usage) + "\n" +
                "화면 흐름 기록: " + onOff(accessibility) + "\n" +
                "알림: " + onOff(hasNotificationPermission()) + "\n" +
                "기록 상태: " + (appSettings.isTrackingPaused() ? "일시정지" : "켜짐") + "\n" +
                "숨긴 앱: " + appSettings.getHiddenPackages().size() + "개\n" +
                "오늘 정리: " + (appSettings.hasGeminiApiKey() ? "준비됨" : "Gemini 설정 필요")
        );
        ((Button) findViewById(R.id.button_permission_setup)).setText(nextPermissionLabel());
        accessibilityHelpText.setText(buildAccessibilityHelp(accessibility, rangeEvents));
        updateModeButtons();
        renderTimeline(rangeEvents);
    }

    private String buildMainBannerTitle(boolean usage, boolean accessibility, List<TimelineEvent> events) {
        if (!usage) {
            return rangeLabel(selectedRange) + " 앱별 사용시간을 계산하려면 권한이 필요합니다.";
        }
        int totalMinutes = totalPhoneMinutes(events);
        int drainMinutes = drainMinutes(events);
        if (totalMinutes <= 0) {
            return rangeLabel(selectedRange) + " 휴대폰 사용 기록이 없습니다.";
        }
        if (drainMinutes > 0) {
            return rangeLabel(selectedRange) + " 앱 사용 " + formatMinutes(totalMinutes);
        }
        return rangeLabel(selectedRange) + " 앱 사용 " + formatMinutes(totalMinutes);
    }

    private String buildMainBannerMeta(boolean usage, boolean accessibility, List<TimelineEvent> events) {
        if (!usage) {
            return "사이드 메뉴에서 다음 권한 설정을 눌러 시작하세요.";
        }
        int totalMinutes = totalPhoneMinutes(events);
        int drainMinutes = drainMinutes(events);
        int switches = switchCount(events);
        List<AppUsageSummary> summaries = appUsageSummaries(events);
        String topApp = summaries.isEmpty() ? "" : summaries.get(0).appName + " " + formatMinutes(summaries.get(0).minutes);
        if (totalMinutes <= 0) {
            return accessibility
                    ? "다른 앱을 30초 이상 사용하고 돌아오면 바로 계산됩니다."
                    : "화면 흐름 권한을 켜면 앱 안에서 무엇을 했는지도 보강됩니다.";
        }
        String line = "총 사용 " + formatMinutes(totalMinutes)
                + " · 새는 시간 " + formatMinutes(drainMinutes)
                + " · 전환 " + switches + "회";
        if (!topApp.isEmpty()) {
            line += " · 최다 사용 " + topApp;
        }
        if (!accessibility) {
            line += "\n화면 흐름 권한을 켜면 앱 안 작업까지 더 정확해집니다.";
        }
        return line;
    }

    private String onOff(boolean value) {
        return value ? "켜짐" : "꺼짐";
    }

    private String nextPermissionLabel() {
        if (!hasUsageAccess()) {
            return "앱 사용 시간 켜기";
        }
        if (!isAccessibilityEnabled()) {
            return "화면 흐름 켜기";
        }
        if (!hasNotificationPermission()) {
            return "알림 허용";
        }
        return "권한 상태 확인";
    }

    private String buildAccessibilityHelp(boolean enabled, List<TimelineEvent> events) {
        int screenEvents = 0;
        long latestScreenAt = 0L;
        for (TimelineEvent event : events) {
            if ("Screen".equals(event.source) || "Accessibility".equals(event.source)) {
                screenEvents++;
                latestScreenAt = Math.max(latestScreenAt, event.timestamp);
            }
        }
        if (!enabled) {
            return "화면 컨텍스트가 꺼져 있습니다.\n접근성 화면에서 제한된 설정 메시지가 뜨면 '제한된 설정 해제 안내'를 먼저 진행한 뒤 AI Timeline screen observer를 켜세요.";
        }
        if (screenEvents == 0) {
            return "화면 컨텍스트는 켜져 있지만 아직 Screen 이벤트가 없습니다.\n다른 앱을 열고 화면을 전환하거나 버튼을 누른 뒤 돌아오세요.";
        }
        return "화면 컨텍스트 ON\n기록된 화면 이벤트: " + screenEvents + "개\n마지막 기록: " + formatTime(latestScreenAt);
    }

    private void openAiSettingsDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_ai_settings);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        EditText apiKeyInput = dialog.findViewById(R.id.input_api_key);
        EditText modelInput = dialog.findViewById(R.id.input_model);
        TextView settingsStatus = dialog.findViewById(R.id.text_settings_status);
        Button testButton = dialog.findViewById(R.id.button_test_api);
        Button listModelsButton = dialog.findViewById(R.id.button_list_models);
        Button saveButton = dialog.findViewById(R.id.button_save_settings);
        Button closeButton = dialog.findViewById(R.id.button_close_settings);

        apiKeyInput.setText(appSettings.getGeminiApiKey());
        modelInput.setText(appSettings.getGeminiModel());
        settingsStatus.setText("모델 ID는 직접 입력할 수 있습니다. 계정에서 gemini-3-flash가 열려 있으면 그대로 사용할 수 있고, 아니면 모델 목록에서 사용 가능한 Flash 계열을 골라 저장하세요.");

        testButton.setOnClickListener(v -> {
            String apiKey = apiKeyInput.getText().toString().trim();
            String modelId = modelInput.getText().toString().trim();
            settingsStatus.setText("Gemini 모델을 확인하는 중...");
            executor.execute(() -> {
                GeminiTimelineClient.Result result = geminiClient.testModel(apiKey, modelId);
                runOnUiThread(() -> settingsStatus.setText(result.text));
            });
        });

        listModelsButton.setOnClickListener(v -> {
            String apiKey = apiKeyInput.getText().toString().trim();
            settingsStatus.setText("사용 가능한 Gemini 모델을 불러오는 중...");
            executor.execute(() -> {
                GeminiTimelineClient.Result result = geminiClient.listModels(apiKey);
                runOnUiThread(() -> settingsStatus.setText(result.text));
            });
        });

        saveButton.setOnClickListener(v -> {
            appSettings.saveGeminiSettings(
                    apiKeyInput.getText().toString(),
                    modelInput.getText().toString()
            );
            showToast("Gemini 설정을 저장했습니다.");
            dialog.dismiss();
            refreshUi();
        });
        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void summarizeTimelineWithGemini() {
        if (!appSettings.hasGeminiApiKey()) {
            showToast("먼저 Gemini API 설정을 저장해 주세요.");
            openAiSettingsDialog();
            return;
        }
        List<TimelineEvent> events = mainTimelineEvents(timelineStore.loadEvents());
        if (events.isEmpty()) {
            showToast("요약할 타임라인이 없습니다.");
            return;
        }
        showToast("Gemini가 정제된 타임라인을 요약하는 중...");
        executor.execute(() -> {
            GeminiTimelineClient.Result result = geminiClient.summarizeTimeline(
                    appSettings.getGeminiApiKey(),
                    appSettings.getGeminiModel(),
                    events
            );
            runOnUiThread(() -> {
                if (result.success) {
                    timelineStore.addEvent(new TimelineEvent(
                            System.currentTimeMillis(),
                            "Gemini",
                            "",
                            "AI 타임라인 요약",
                            result.text
                    ));
                    refreshUi();
                } else {
                    showToast(result.text);
                }
            });
        });
    }

    private void renderTimeline() {
        renderTimeline(mainTimelineEvents(timelineStore.loadEvents()));
    }

    private void renderTimeline(List<TimelineEvent> events) {
        timelineContainer.removeAllViews();
        if (events.isEmpty() || totalPhoneMinutes(events) <= 0) {
            renderEmptyTimelineState();
            return;
        }
        renderAppUsageDashboard(events);
    }

    private void renderEmptyTimelineState() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setBackgroundResource(R.drawable.bg_panel_soft);
        empty.setPadding(dp(14), dp(12), dp(14), dp(12));
        empty.addView(buildText("선택한 기간에 앱 사용 기록이 없습니다.", TEXT_PRIMARY, 16, true));
        empty.addView(buildText(hasUsageAccess()
                ? "다른 앱을 30초 이상 사용한 뒤 돌아오면 앱별 사용시간과 행동 요약이 갱신됩니다."
                : "사이드 메뉴에서 앱 사용 시간을 먼저 켜 주세요.", TEXT_SECONDARY, 13, false));
        timelineContainer.addView(empty);
    }

    private List<TimelineEvent> mainTimelineEvents(List<TimelineEvent> events) {
        java.util.ArrayList<TimelineEvent> visible = new java.util.ArrayList<>();
        for (TimelineEvent event : events) {
            if (isEventInSelectedRange(event) && shouldShowInMainTimeline(event, events)) {
                visible.add(event);
            }
        }
        return visible;
    }

    private List<TimelineEvent> selectedRangeEvents(List<TimelineEvent> events) {
        java.util.ArrayList<TimelineEvent> selected = new java.util.ArrayList<>();
        for (TimelineEvent event : events) {
            if (isEventInSelectedRange(event) && !appSettings.isPackageHidden(event.packageName)) {
                selected.add(event);
            }
        }
        return selected;
    }

    private boolean isEventInSelectedRange(TimelineEvent event) {
        if (event == null) {
            return false;
        }
        long start = rangeStartMillis(selectedRange);
        long now = System.currentTimeMillis();
        return event.timestamp >= start && event.timestamp <= now + 60L * 1000L;
    }

    private boolean shouldShowInMainTimeline(TimelineEvent event, List<TimelineEvent> allEvents) {
        if (event == null) {
            return false;
        }
        if (appSettings.isPackageHidden(event.packageName)) {
            return false;
        }
        if ("UsageStats".equals(event.source) || "Private".equals(event.source) || "Gemini".equals(event.source) || "Manual".equals(event.source)) {
            return true;
        }
        if ("Screen".equals(event.source)) {
            return !hasNearbyUsageEvent(event, allEvents);
        }
        return false;
    }

    private boolean hasNearbyUsageEvent(TimelineEvent screenEvent, List<TimelineEvent> events) {
        if (screenEvent.packageName == null || screenEvent.packageName.isEmpty()) {
            return false;
        }
        for (TimelineEvent event : events) {
            boolean samePackage = screenEvent.packageName.equals(event.packageName);
            boolean isUsage = "UsageStats".equals(event.source) || "Private".equals(event.source) || "Manual".equals(event.source);
            boolean close = Math.abs(screenEvent.timestamp - event.timestamp) <= 20L * 60L * 1000L;
            if (samePackage && isUsage && close) {
                return true;
            }
        }
        return false;
    }

    private void renderAppUsageDashboard(List<TimelineEvent> events) {
        List<AppUsageSummary> appSummaries = appUsageSummaries(events);
        int totalMinutes = totalPhoneMinutes(events);
        int drainMinutes = drainMinutes(events);

        LinearLayout overview = new LinearLayout(this);
        overview.setOrientation(LinearLayout.VERTICAL);
        overview.setBackgroundResource(R.drawable.bg_panel);
        overview.setPadding(dp(16), dp(14), dp(16), dp(14));
        overview.addView(buildText(rangeLabel(selectedRange) + " 앱별 사용시간", TEXT_PRIMARY, 19, true));
        overview.addView(buildText(formatMinutes(totalMinutes), TEXT_PRIMARY, 30, true));
        overview.addView(buildText("앱 " + appSummaries.size() + "개 · 새는 시간 " + formatMinutes(drainMinutes)
                + " · 최근순 " + bucketUnitLabel(selectedRange) + " 보기", TEXT_SECONDARY, 13, false));
        timelineContainer.addView(overview);

        renderAppUsageSection(appSummaries, events);
        renderTimeBucketSection(events);
    }

    private void renderAppUsageSection(List<AppUsageSummary> summaries, List<TimelineEvent> scopeEvents) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundResource(R.drawable.bg_panel);
        section.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sectionParams.setMargins(0, dp(12), 0, 0);
        section.setLayoutParams(sectionParams);
        section.addView(buildText("앱별 총 사용시간", TEXT_PRIMARY, 18, true));
        section.addView(buildText("앱을 누르면 그 안에서 무엇을 했는지 요약해서 봅니다.", TEXT_SECONDARY, 13, false));

        if (summaries.isEmpty()) {
            section.addView(buildText("선택한 기간에 앱 사용 기록이 없습니다.", TEXT_SECONDARY, 13, false));
        }
        for (int i = 0; i < Math.min(12, summaries.size()); i++) {
            AppUsageSummary summary = summaries.get(i);
            section.addView(appUsageRow(summary, scopeEvents, i == 0));
        }
        timelineContainer.addView(section);
    }

    private LinearLayout appUsageRow(AppUsageSummary summary, List<TimelineEvent> scopeEvents, boolean strong) {
        return appUsageRow(summary, scopeEvents, strong, rangeLabel(selectedRange));
    }

    private LinearLayout appUsageRow(AppUsageSummary summary, List<TimelineEvent> scopeEvents, boolean strong, String scopeTitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundResource(R.drawable.bg_panel_soft);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(10), 0, 0);
        row.setLayoutParams(params);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(buildText(summary.appName, TEXT_PRIMARY, 15, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(buildText(formatMinutes(summary.minutes), strong ? BRAND_BLUE : TEXT_PRIMARY, 15, true));
        row.addView(top);
        row.addView(buildText(summary.category + " · " + summary.events.size() + "개 사용 구간", TEXT_TERTIARY, 12, false));
        row.addView(buildText(actionSummaryForApp(summary.packageName, scopeEvents), TEXT_SECONDARY, 13, false));
        row.setOnClickListener(v -> showAppUsageDetail(summary, scopeEvents, scopeTitle));
        return row;
    }

    private void renderTimeBucketSection(List<TimelineEvent> events) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundResource(R.drawable.bg_panel);
        section.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sectionParams.setMargins(0, dp(12), 0, 0);
        section.setLayoutParams(sectionParams);
        section.addView(buildText(bucketUnitLabel(selectedRange) + "별 사용 내역", TEXT_PRIMARY, 18, true));
        section.addView(buildText("최근 구간이 가장 위에 옵니다. 박스를 누르면 해당 구간의 앱별 행동이 열립니다.", TEXT_SECONDARY, 13, false));

        List<TimeBucket> buckets = timeBuckets(events);
        for (TimeBucket bucket : buckets) {
            section.addView(timeBucketRow(bucket));
        }
        timelineContainer.addView(section);
    }

    private LinearLayout timeBucketRow(TimeBucket bucket) {
        int totalMinutes = totalPhoneMinutes(bucket.events);
        List<AppUsageSummary> summaries = appUsageSummaries(bucket.events);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackgroundResource(R.drawable.bg_timeline_row);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(10), 0, 0);
        row.setLayoutParams(params);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(buildText(bucket.title, TEXT_PRIMARY, 15, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(buildText(formatMinutes(totalMinutes), totalMinutes > 0 ? BRAND_BLUE : TEXT_TERTIARY, 14, true));
        row.addView(top);
        String topApp = summaries.isEmpty() ? "앱 사용 없음" : summaries.get(0).appName + " " + formatMinutes(summaries.get(0).minutes);
        row.addView(buildText(bucket.subtitle + " · " + topApp, TEXT_SECONDARY, 13, false));
        if (!summaries.isEmpty()) {
            row.addView(buildText(actionSummaryForApp(summaries.get(0).packageName, bucket.events), TEXT_TERTIARY, 12, false));
        }
        row.setOnClickListener(v -> showBucketDetail(bucket));
        return row;
    }

    private List<AppUsageSummary> appUsageSummaries(List<TimelineEvent> events) {
        Map<String, AppUsageSummary> map = new HashMap<>();
        for (TimelineEvent event : events) {
            if (!isCountablePhoneUsageEvent(event, events)) {
                continue;
            }
            String packageName = event.packageName == null ? "" : event.packageName;
            if (packageName.trim().isEmpty()) {
                continue;
            }
            AppUsageSummary summary = map.get(packageName);
            if (summary == null) {
                summary = new AppUsageSummary();
                summary.packageName = packageName;
                summary.appName = displayTitle(event);
                summary.category = AppNameResolver.category(packageName);
                map.put(packageName, summary);
            }
            summary.minutes += durationMinutes(event);
            summary.events.add(event);
        }
        List<AppUsageSummary> summaries = new ArrayList<>(map.values());
        summaries.sort((a, b) -> Integer.compare(b.minutes, a.minutes));
        return summaries;
    }

    private String actionSummaryForApp(String packageName, List<TimelineEvent> scopeEvents) {
        List<String> actions = actionLinesForApp(packageName, scopeEvents, 2);
        if (actions.isEmpty()) {
            return isAccessibilityEnabled()
                    ? "앱 사용 시간은 잡혔지만 화면 안 행동 기록은 아직 부족합니다."
                    : "화면 흐름 권한을 켜면 이 앱 안에서 한 행동 요약이 채워집니다.";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) {
                builder.append(" · ");
            }
            builder.append(actions.get(i));
        }
        return builder.toString();
    }

    private List<String> actionLinesForApp(String packageName, List<TimelineEvent> scopeEvents, int limit) {
        List<String> actions = new ArrayList<>();
        if (packageName == null || packageName.trim().isEmpty()) {
            return actions;
        }
        for (TimelineEvent event : scopeEvents) {
            if (!packageName.equals(event.packageName)) {
                continue;
            }
            if (!"Screen".equals(event.source) && !"Accessibility".equals(event.source)) {
                continue;
            }
            String summary = displaySummary(event);
            if (summary.trim().isEmpty() || actions.contains(summary)) {
                continue;
            }
            actions.add(summary);
            if (actions.size() >= limit) {
                break;
            }
        }
        return actions;
    }

    private void showBucketDetail(TimeBucket bucket) {
        Dialog dialog = buildSheetDialog();
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#F7F9FC"));
        LinearLayout shell = buildDialogShell();
        scrollView.addView(shell);
        dialog.setContentView(scrollView);

        shell.addView(buildText(bucket.title, TEXT_PRIMARY, 22, true));
        shell.addView(buildText(bucket.subtitle + " · 총 사용 " + formatMinutes(totalPhoneMinutes(bucket.events)), TEXT_SECONDARY, 13, false));

        List<AppUsageSummary> summaries = appUsageSummaries(bucket.events);
        if (summaries.isEmpty()) {
            shell.addView(buildText("이 구간에는 앱 사용 기록이 없습니다.", TEXT_SECONDARY, 14, false));
        }
        for (AppUsageSummary summary : summaries) {
            shell.addView(appUsageRow(summary, bucket.events, false, bucket.title));
        }
        shell.addView(dialogButton("닫기", true, v -> dialog.dismiss()));
        showSheetDialog(dialog);
    }

    private void showRawRecordsDialog(List<TimelineEvent> events) {
        Dialog dialog = buildSheetDialog();
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#F7F9FC"));
        LinearLayout shell = buildDialogShell();
        scrollView.addView(shell);
        dialog.setContentView(scrollView);

        shell.addView(buildText("원본 기록", TEXT_PRIMARY, 22, true));
        shell.addView(buildText(rangeLabel(selectedRange) + " · 분석 계산에 쓰인 이벤트입니다.", TEXT_SECONDARY, 13, false));
        events.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        if (events.isEmpty()) {
            shell.addView(buildText("선택한 기간에 원본 기록이 없습니다.", TEXT_SECONDARY, 13, false));
        }
        for (int i = 0; i < Math.min(40, events.size()); i++) {
            TimelineEvent event = events.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_panel_soft);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, dp(8), 0, 0);
            row.setLayoutParams(params);
            row.addView(buildText(formatDateTime(event.timestamp) + " · " + displayTitle(event), TEXT_PRIMARY, 13, true));
            row.addView(buildText(activityLine(event), TEXT_SECONDARY, 12, false));
            row.setOnClickListener(v -> showEventDetail(event));
            shell.addView(row);
        }
        shell.addView(dialogButton("닫기", true, v -> dialog.dismiss()));
        showSheetDialog(dialog);
    }

    private void showAppUsageDetail(AppUsageSummary summary, List<TimelineEvent> scopeEvents, String scopeTitle) {
        Dialog dialog = buildSheetDialog();
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#F7F9FC"));
        LinearLayout shell = buildDialogShell();
        scrollView.addView(shell);
        dialog.setContentView(scrollView);

        shell.addView(buildText(summary.appName, TEXT_PRIMARY, 23, true));
        shell.addView(buildText(scopeTitle + " · " + summary.category + " · 총 " + formatMinutes(summary.minutes), BRAND_BLUE, 13, true));
        shell.addView(sectionTitle("행동 요약"));
        List<String> actions = actionLinesForApp(summary.packageName, scopeEvents, 8);
        if (actions.isEmpty()) {
            shell.addView(buildText(actionSummaryForApp(summary.packageName, scopeEvents), TEXT_SECONDARY, 13, false));
        } else {
            for (String action : actions) {
                shell.addView(buildText("· " + action, TEXT_SECONDARY, 13, false));
            }
        }

        shell.addView(sectionTitle("사용 구간"));
        summary.events.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        for (TimelineEvent event : summary.events) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_panel_soft);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            rowParams.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(rowParams);
            row.addView(buildText(formatDateTime(event.timestamp) + " · " + formatMinutes(durationMinutes(event)), TEXT_PRIMARY, 13, true));
            row.addView(buildText(activityLine(event), TEXT_SECONDARY, 13, false));
            row.setOnClickListener(v -> showEventDetail(event));
            shell.addView(row);
        }

        shell.addView(dialogButton("닫기", true, v -> dialog.dismiss()));
        showSheetDialog(dialog);
    }


    private void renderLiveTimeline(List<TimelineEvent> events) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundResource(R.drawable.bg_panel);
        header.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerParams.setMargins(0, 0, 0, dp(12));
        header.setLayoutParams(headerParams);
        header.addView(buildText("원본 기록", TEXT_PRIMARY, 19, true));
        header.addView(buildText("시간 손실 계산에 쓰인 앱 사용 블록입니다. 분석이 이상할 때만 확인하면 됩니다.", TEXT_SECONDARY, 13, false));
        timelineContainer.addView(header);

        for (int i = 0; i < events.size(); i++) {
            TimelineEvent event = events.get(i);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setBackgroundResource(i == 0 ? R.drawable.bg_timeline_current : R.drawable.bg_timeline_row);
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, 0, dp(8));
            item.setLayoutParams(params);

            decorateTimelineCard(item, event);
            timelineContainer.addView(item);
        }
    }

    private void renderGridTimeline(List<TimelineEvent> events) {
        renderLossOverview(events);
        List<TimelineEvent> scheduleEvents = scheduleEvents(events);
        if (scheduleEvents.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setBackgroundResource(R.drawable.bg_panel_soft);
            empty.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            emptyParams.setMargins(0, dp(12), 0, 0);
            empty.setLayoutParams(emptyParams);
            empty.addView(buildText("손실 지도에 표시할 앱 사용이 아직 없습니다.", TEXT_PRIMARY, 15, true));
            empty.addView(buildText("기록이 쌓이면 하루 지도처럼 어느 시간대에 시간이 새었는지 보입니다.", TEXT_SECONDARY, 13, false));
            timelineContainer.addView(empty);
            return;
        }

        LinearLayout canvas = new LinearLayout(this);
        canvas.setOrientation(LinearLayout.VERTICAL);
        canvas.setBackgroundResource(R.drawable.bg_timeline_canvas);
        canvas.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams canvasParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        canvasParams.setMargins(0, dp(12), 0, 0);
        canvas.setLayoutParams(canvasParams);

        int startHour = Math.max(6, Math.min(getMinHour(scheduleEvents), getHour(System.currentTimeMillis()) - 2));
        int endHour = Math.min(23, Math.max(getMaxHour(scheduleEvents), getHour(System.currentTimeMillis()) + 1));
        for (int hour = startHour; hour <= endHour; hour++) {
            LinearLayout hourRow = new LinearLayout(this);
            hourRow.setOrientation(LinearLayout.HORIZONTAL);
            hourRow.setPadding(0, 0, 0, dp(8));

            TextView hourText = buildText(String.format(Locale.KOREA, "%02d", hour), TEXT_TERTIARY, 12, true);
            hourText.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            hourRow.addView(hourText, new LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.MATCH_PARENT));

            LinearLayout blockColumn = new LinearLayout(this);
            blockColumn.setOrientation(LinearLayout.VERTICAL);
            blockColumn.setPadding(0, 0, 0, 0);

            int count = 0;
            for (TimelineEvent event : scheduleEvents) {
                int eventHour = getHour(event.timestamp);
                if (eventHour == hour) {
                    blockColumn.addView(scheduleBlock(event));
                    count++;
                }
            }
            if (count == 0) {
                TextView empty = buildText(" ", TEXT_TERTIARY, 12, false);
                empty.setMinHeight(dp(34));
                blockColumn.addView(empty);
            }
            hourRow.addView(blockColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            canvas.addView(hourRow);
        }
        timelineContainer.addView(canvas);
    }

    private void renderFocusTimeline(List<TimelineEvent> events) {
        Map<String, Integer> categoryMinutes = new HashMap<>();
        Map<String, Integer> drainCategoryMinutes = new HashMap<>();
        Map<String, Integer> appMinutes = new HashMap<>();
        int privateCount = 0;
        int maxMinutes = 0;
        TimelineEvent longestEvent = null;
        for (TimelineEvent event : events) {
            if (!isPhoneUsageEvent(event)) {
                continue;
            }
            int minutes = durationMinutes(event);
            String category = AppNameResolver.category(event.packageName);
            String appName = displayTitle(event);
            categoryMinutes.put(category, categoryMinutes.getOrDefault(category, 0) + minutes);
            appMinutes.put(appName, appMinutes.getOrDefault(appName, 0) + minutes);
            if (isDrainEvent(event)) {
                drainCategoryMinutes.put(category, drainCategoryMinutes.getOrDefault(category, 0) + minutes);
            }
            if (minutes > maxMinutes) {
                maxMinutes = minutes;
                longestEvent = event;
            }
            if ("Private".equals(event.source)) {
                privateCount++;
            }
        }

        int totalMinutes = totalPhoneMinutes(events);
        int drainMinutes = drainMinutes(events);
        int usefulMinutes = Math.max(0, totalMinutes - drainMinutes);
        int switches = switchCount(events);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_panel);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        panel.addView(buildText("원인 분석", TEXT_PRIMARY, 20, true));
        panel.addView(buildText(lossJudgement(drainMinutes, totalMinutes, switches), drainMinutes > 0 ? LOSS_RED : RECOVERY_TEAL, 15, true));
        panel.addView(buildText("총 사용 " + formatMinutes(totalMinutes)
                + " · 새는 시간 " + formatMinutes(drainMinutes)
                + " · 필요한 사용 " + formatMinutes(usefulMinutes), TEXT_SECONDARY, 13, false));
        panel.addView(buildText("전환 " + switches + "회 · 비공개 처리 " + privateCount + "개 · 기준 " + appSettings.getGoalLabel(), TEXT_SECONDARY, 13, false));
        panel.addView(insightRow("가장 많이 가져간 앱", topEntryLabel(appMinutes)));
        panel.addView(insightRow("시간이 샌 카테고리", topEntryLabel(drainCategoryMinutes)));
        panel.addView(insightRow("최장 연속 사용", longestEvent == null ? "아직 없음" : displayTitle(longestEvent) + " · " + formatMinutes(maxMinutes)));
        panel.addView(insightRow("집중을 끊은 전환", switches + "회"));
        String memo = appSettings.getTodayMemo();
        if (memo != null && !memo.trim().isEmpty()) {
            panel.addView(buildText("메모: " + memo.trim(), TEXT_SECONDARY, 13, false));
        }
        timelineContainer.addView(panel);

        renderMapSection("시간이 샌 카테고리", drainCategoryMinutes);
        renderMapSection("가장 많이 가져간 앱", appMinutes);
        renderMapSection("전체 사용 카테고리", categoryMinutes);
    }

    private void renderLossOverview(List<TimelineEvent> events) {
        int totalMinutes = totalPhoneMinutes(events);
        int drainMinutes = drainMinutes(events);
        int usefulMinutes = Math.max(0, totalMinutes - drainMinutes);
        int switches = switchCount(events);
        int goalMinutes = appSettings.getGoalMinutes();
        int progress = Math.min(100, goalMinutes <= 0 ? 0 : (int) ((drainMinutes * 100f) / goalMinutes));

        LinearLayout overview = new LinearLayout(this);
        overview.setOrientation(LinearLayout.VERTICAL);
        overview.setBackgroundResource(R.drawable.bg_panel);
        overview.setPadding(dp(16), dp(14), dp(16), dp(14));
        overview.addView(buildText("오늘 시간이 새어 나간 곳", TEXT_PRIMARY, 18, true));
        overview.addView(buildText("삭제된 시간 " + formatMinutes(drainMinutes), drainMinutes > 0 ? LOSS_RED : RECOVERY_TEAL, 27, true));
        overview.addView(buildText("총 휴대폰 사용 " + formatMinutes(totalMinutes)
                + " · 필요한 사용 " + formatMinutes(usefulMinutes)
                + " · 전환 " + switches + "회", TEXT_SECONDARY, 13, false));
        overview.addView(buildText(appSettings.getGoalLabel() + " 기준 " + formatMinutes(goalMinutes)
                + " 중 " + progress + "%", TEXT_TERTIARY, 12, false));
        String memo = appSettings.getTodayMemo();
        if (memo != null && !memo.trim().isEmpty()) {
            overview.addView(buildText("오늘 메모: " + memo.trim(), TEXT_SECONDARY, 13, false));
        }
        timelineContainer.addView(overview);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams metricsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        metricsParams.setMargins(0, dp(10), 0, 0);
        metrics.setLayoutParams(metricsParams);
        metrics.addView(metricCard("가장 큰 구멍", topDrainLabel(events), LOSS_RED, true));
        metrics.addView(metricCard("필요한 사용", formatMinutes(usefulMinutes), RECOVERY_TEAL, false));
        metrics.addView(metricCard("앱 전환", switches + "회", BRAND_BLUE, false));
        timelineContainer.addView(metrics);
    }

    private TextView scheduleBlock(TimelineEvent event) {
        int minutes = durationMinutes(event);
        String category = AppNameResolver.category(event.packageName);
        String prefix = isDrainEvent(event) ? "삭제됨" : ("Manual".equals(event.source) ? "회수됨" : "필요한 사용");
        String severity = "Manual".equals(event.source) ? "집중 회수" : severityLabel(minutes);
        String line = prefix + " " + formatMinutes(minutes) + " · " + category + " · " + severity;
        TextView block = buildText(formatTime(event.timestamp) + "  " + displayTitle(event) + "\n" + line, TEXT_PRIMARY, 13, true);
        block.setBackground(makeBlockDrawable(event));
        block.setPadding(dp(12), dp(8), dp(12), dp(8));
        block.setMinHeight(dp(Math.min(108, Math.max(48, minutes))));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        block.setLayoutParams(params);
        block.setOnClickListener(v -> showEventDetail(event));
        return block;
    }

    private GradientDrawable makeBlockDrawable(TimelineEvent event) {
        String fill = categoryFillColor(event);
        String stroke = categoryStrokeColor(event);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(fill));
        drawable.setStroke(dp(1), Color.parseColor(stroke));
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private void renderMapSection(String title, Map<String, Integer> values) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setBackgroundResource(R.drawable.bg_panel);
        section.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sectionParams.setMargins(0, dp(12), 0, 0);
        section.setLayoutParams(sectionParams);
        section.addView(buildText(title, TEXT_PRIMARY, 18, true));

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(values.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (entries.isEmpty()) {
            section.addView(buildText("아직 기록이 없습니다.", TEXT_SECONDARY, 13, false));
        }
        int maxValue = 1;
        for (Map.Entry<String, Integer> entry : entries) {
            maxValue = Math.max(maxValue, entry.getValue());
        }
        for (int i = 0; i < Math.min(entries.size(), 5); i++) {
            Map.Entry<String, Integer> entry = entries.get(i);
            section.addView(mapValueRow(entry.getKey(), entry.getValue(), maxValue, i == 0));
        }
        timelineContainer.addView(section);
    }

    private String lossJudgement(int drainMinutes, int totalMinutes, int switchCount) {
        if (totalMinutes <= 0) {
            return "아직 판단할 사용 시간이 없습니다.";
        }
        if (drainMinutes >= 120) {
            return "회복하기 어려운 시간이 크게 새고 있어요.";
        }
        if (drainMinutes >= 45) {
            return "오늘은 습관적 사용이 눈에 띄는 날이에요.";
        }
        if (switchCount >= 12) {
            return "짧은 확인이 반복되며 집중이 자주 끊겼어요.";
        }
        return "필요한 사용 쪽으로 잘 묶인 날이에요.";
    }

    private LinearLayout metricCard(String label, String value, String color, boolean emphasize) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_panel);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        );
        params.setMargins(0, 0, dp(7), 0);
        card.setLayoutParams(params);
        card.setMinimumHeight(dp(74));
        card.addView(buildText(label, TEXT_TERTIARY, 11, false));
        card.addView(buildText(value == null || value.trim().isEmpty() ? "없음" : value, color, emphasize ? 15 : 14, true));
        return card;
    }

    private LinearLayout insightRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(10), 0, 0);
        row.setLayoutParams(params);
        row.addView(buildText(label, TEXT_TERTIARY, 12, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(buildText(value == null || value.trim().isEmpty() ? "없음" : value, TEXT_PRIMARY, 13, true));
        return row;
    }

    private LinearLayout mapValueRow(String label, int value, int maxValue, boolean strong) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(12), 0, 0);
        row.setLayoutParams(params);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.addView(buildText(label, TEXT_PRIMARY, 14, strong), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        line.addView(buildText(formatMinutes(value), TEXT_SECONDARY, 13, strong));
        row.addView(line);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(makeRoundedDrawable("#EEF2F6", "#EEF2F6", 4));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(7)
        );
        barParams.setMargins(0, dp(6), 0, 0);
        bar.setLayoutParams(barParams);

        TextView fill = new TextView(this);
        fill.setBackground(makeRoundedDrawable(strong ? BRAND_BLUE : "#AAB7C4", strong ? BRAND_BLUE : "#AAB7C4", 4));
        bar.addView(fill, new LinearLayout.LayoutParams(0, dp(7), Math.max(1, value)));

        int rest = Math.max(0, maxValue - value);
        TextView spacer = new TextView(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, dp(7), rest));
        row.addView(bar);
        return row;
    }

    private GradientDrawable makeRoundedDrawable(String fill, String stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(fill));
        drawable.setStroke(dp(1), Color.parseColor(stroke));
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private List<TimelineEvent> scheduleEvents(List<TimelineEvent> events) {
        List<TimelineEvent> copy = new ArrayList<>();
        for (TimelineEvent event : events) {
            if (isPhoneUsageEvent(event) || "Manual".equals(event.source)) {
                copy.add(event);
            }
        }
        copy.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        return copy;
    }

    private List<TimeBucket> timeBuckets(List<TimelineEvent> events) {
        List<TimeBucket> buckets = new ArrayList<>();
        long now = System.currentTimeMillis();
        if (RANGE_DAY.equals(selectedRange)) {
            Calendar cursor = Calendar.getInstance(Locale.KOREA);
            cursor.setTimeInMillis(now);
            cursor.set(Calendar.MINUTE, 0);
            cursor.set(Calendar.SECOND, 0);
            cursor.set(Calendar.MILLISECOND, 0);
            long dayStart = startOfCurrentDayMillis();
            while (cursor.getTimeInMillis() >= dayStart) {
                long start = cursor.getTimeInMillis();
                long end = Math.min(now + 1L, start + 60L * 60L * 1000L);
                addTimeBucket(buckets, formatHourRange(start), "오늘", start, end, events);
                cursor.add(Calendar.HOUR_OF_DAY, -1);
            }
            return buckets;
        }
        if (RANGE_WEEK.equals(selectedRange) || RANGE_MONTH.equals(selectedRange)) {
            int days = RANGE_WEEK.equals(selectedRange) ? 7 : 30;
            Calendar cursor = Calendar.getInstance(Locale.KOREA);
            cursor.setTimeInMillis(startOfCurrentDayMillis());
            for (int i = 0; i < days; i++) {
                long start = cursor.getTimeInMillis();
                long end = i == 0 ? now + 1L : start + 24L * 60L * 60L * 1000L;
                addTimeBucket(buckets, formatDayLabel(start), i == 0 ? "오늘" : "하루", start, end, events);
                cursor.add(Calendar.DAY_OF_YEAR, -1);
            }
            return buckets;
        }

        Calendar cursor = Calendar.getInstance(Locale.KOREA);
        cursor.setTimeInMillis(now);
        cursor.set(Calendar.DAY_OF_MONTH, 1);
        cursor.set(Calendar.HOUR_OF_DAY, 0);
        cursor.set(Calendar.MINUTE, 0);
        cursor.set(Calendar.SECOND, 0);
        cursor.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < 12; i++) {
            long start = cursor.getTimeInMillis();
            Calendar next = (Calendar) cursor.clone();
            next.add(Calendar.MONTH, 1);
            long end = i == 0 ? now + 1L : next.getTimeInMillis();
            addTimeBucket(buckets, formatMonthLabel(start), i == 0 ? "이번 달" : "한 달", start, end, events);
            cursor.add(Calendar.MONTH, -1);
        }
        return buckets;
    }

    private void addTimeBucket(List<TimeBucket> buckets, String title, String subtitle, long start, long end, List<TimelineEvent> events) {
        TimeBucket bucket = new TimeBucket();
        bucket.title = title;
        bucket.subtitle = subtitle;
        bucket.start = start;
        bucket.end = end;
        for (TimelineEvent event : events) {
            if (event.timestamp >= start && event.timestamp < end) {
                bucket.events.add(event);
            }
        }
        bucket.events.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        buckets.add(bucket);
    }

    private boolean isPhoneUsageEvent(TimelineEvent event) {
        if (event == null) {
            return false;
        }
        return "UsageStats".equals(event.source)
                || "Private".equals(event.source)
                || "Screen".equals(event.source);
    }

    private boolean isCountablePhoneUsageEvent(TimelineEvent event, List<TimelineEvent> events) {
        if (event == null) {
            return false;
        }
        if ("UsageStats".equals(event.source) || "Private".equals(event.source)) {
            return true;
        }
        return "Screen".equals(event.source) && !hasNearbyUsageEvent(event, events);
    }

    private boolean isDrainEvent(TimelineEvent event) {
        if (!isPhoneUsageEvent(event)) {
            return false;
        }
        String category = AppNameResolver.category(event.packageName);
        if (isDrainCategory(category)) {
            return true;
        }
        return "커뮤니케이션".equals(category) && durationMinutes(event) >= 15;
    }

    private boolean isDrainCategory(String category) {
        return "영상".equals(category)
                || "소셜".equals(category)
                || "웹 탐색".equals(category)
                || "기타".equals(category);
    }

    private int totalPhoneMinutes(List<TimelineEvent> events) {
        int total = 0;
        for (TimelineEvent event : events) {
            if (isCountablePhoneUsageEvent(event, events)) {
                total += durationMinutes(event);
            }
        }
        return total;
    }

    private int drainMinutes(List<TimelineEvent> events) {
        int total = 0;
        for (TimelineEvent event : events) {
            if (isCountablePhoneUsageEvent(event, events) && isDrainEvent(event)) {
                total += durationMinutes(event);
            }
        }
        return total;
    }

    private int switchCount(List<TimelineEvent> events) {
        int phoneEvents = 0;
        for (TimelineEvent event : events) {
            if (isCountablePhoneUsageEvent(event, events)) {
                phoneEvents++;
            }
        }
        return Math.max(0, phoneEvents - 1);
    }

    private String topDrainLabel(List<TimelineEvent> events) {
        TimelineEvent top = null;
        int maxMinutes = 0;
        for (TimelineEvent event : events) {
            if (!isDrainEvent(event)) {
                continue;
            }
            int minutes = durationMinutes(event);
            if (minutes > maxMinutes) {
                maxMinutes = minutes;
                top = event;
            }
        }
        return top == null ? "" : displayTitle(top) + " · " + formatMinutes(maxMinutes);
    }

    private String topEntryLabel(Map<String, Integer> values) {
        if (values == null || values.isEmpty()) {
            return "없음";
        }
        Map.Entry<String, Integer> top = null;
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            if (top == null || entry.getValue() > top.getValue()) {
                top = entry;
            }
        }
        return top == null ? "없음" : top.getKey() + " · " + formatMinutes(top.getValue());
    }

    private String severityLabel(int minutes) {
        if (minutes < 5) {
            return "짧은 확인";
        }
        if (minutes <= 15) {
            return "주의 분산";
        }
        if (minutes <= 45) {
            return "긴 소비";
        }
        return "시간 손실 큼";
    }

    private void decorateTimelineCard(LinearLayout item, TimelineEvent event) {
        TextView time = buildText(formatTime(event.timestamp), TEXT_TERTIARY, 12, true);
        time.setGravity(android.view.Gravity.TOP);
        item.addView(time, new LinearLayout.LayoutParams(dp(48), LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        item.addView(content, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        content.addView(buildText(displayTitle(event), TEXT_PRIMARY, 16, true));
        content.addView(buildText(activityLine(event), TEXT_SECONDARY, 13, false));

        List<TimelineEvent> related = relatedEvents(event);
        if (!related.isEmpty()) {
            content.addView(buildText("화면 흐름 " + related.size() + "개", TEXT_TERTIARY, 11, false));
        }
        item.setOnClickListener(v -> showEventDetail(event));
    }

    private void showEventDetail(TimelineEvent event) {
        Dialog dialog = new Dialog(this);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#F7F9FC"));
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(20), dp(18), dp(20), dp(20));
        scrollView.addView(shell);

        shell.addView(buildText(displayTitle(event), TEXT_PRIMARY, 23, true));
        shell.addView(buildText(formatTime(event.timestamp) + " · " + sourceLabel(event.source) + " · " + AppNameResolver.category(event.packageName), BRAND_BLUE, 13, true));
        shell.addView(buildText(displaySummary(event), TEXT_SECONDARY, 14, false));

        LinearLayout privacyPanel = new LinearLayout(this);
        privacyPanel.setOrientation(LinearLayout.VERTICAL);
        privacyPanel.setBackgroundResource(R.drawable.bg_panel_soft);
        privacyPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        panelParams.setMargins(0, dp(14), 0, 0);
        privacyPanel.setLayoutParams(panelParams);
        privacyPanel.addView(buildText("기록 상태", TEXT_PRIMARY, 15, true));
        privacyPanel.addView(buildText(buildPrivacyLine(event), TEXT_SECONDARY, 13, false));
        shell.addView(privacyPanel);

        List<TimelineEvent> related = relatedEvents(event);
        shell.addView(sectionTitle("관련 화면 기록"));
        if (related.isEmpty()) {
            shell.addView(buildText("아직 이 앱의 화면 컨텍스트 기록이 없습니다. 접근성 권한이 켜져 있어야 세부 작업이 쌓입니다.", TEXT_SECONDARY, 13, false));
        } else {
            for (TimelineEvent relatedEvent : related) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setBackgroundResource(R.drawable.bg_panel_soft);
                row.setPadding(dp(12), dp(10), dp(12), dp(10));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                rowParams.setMargins(0, 0, 0, dp(8));
                row.setLayoutParams(rowParams);
                row.addView(buildText(formatTime(relatedEvent.timestamp) + " · " + sourceLabel(relatedEvent.source), BRAND_BLUE, 12, true));
                row.addView(buildText(displaySummary(relatedEvent), TEXT_SECONDARY, 13, false));
                shell.addView(row);
            }
        }

        if (event.packageName != null && !event.packageName.trim().isEmpty() && !"manual.activity".equals(event.packageName)) {
            shell.addView(dialogButton("이 앱 숨기기", false, v -> {
                appSettings.hidePackage(event.packageName);
                dialog.dismiss();
                refreshUi();
            }));
        }

        Button close = new Button(this);
        close.setText("닫기");
        close.setAllCaps(false);
        close.setTextColor(Color.parseColor("#FFFFFF"));
        close.setTextSize(14);
        close.setTypeface(close.getTypeface(), android.graphics.Typeface.BOLD);
        close.setBackgroundResource(R.drawable.bg_primary_button);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        closeParams.setMargins(0, dp(16), 0, 0);
        close.setLayoutParams(closeParams);
        close.setOnClickListener(v -> dialog.dismiss());
        shell.addView(close);

        dialog.setContentView(scrollView);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.94f);
            dialog.getWindow().setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private TextView sectionTitle(String text) {
        TextView title = buildText(text, TEXT_PRIMARY, 16, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(18), 0, dp(8));
        title.setLayoutParams(params);
        return title;
    }

    private List<TimelineEvent> relatedEvents(TimelineEvent event) {
        java.util.ArrayList<TimelineEvent> related = new java.util.ArrayList<>();
        if (event.packageName == null || event.packageName.isEmpty()) {
            return related;
        }
        List<TimelineEvent> events = timelineStore.loadEvents();
        for (TimelineEvent candidate : events) {
            if (candidate == event) {
                continue;
            }
            boolean samePackage = event.packageName.equals(candidate.packageName);
            boolean closeTime = Math.abs(event.timestamp - candidate.timestamp) <= 20L * 60L * 1000L;
            boolean usefulSource = "Screen".equals(candidate.source) || "Accessibility".equals(candidate.source);
            if (samePackage && closeTime && usefulSource) {
                related.add(candidate);
            }
            if (related.size() >= 8) {
                break;
            }
        }
        return related;
    }

    private String displayTitle(TimelineEvent event) {
        String friendly = AppNameResolver.friendlyName(this, event.packageName, event.title);
        if ("Gemini".equals(event.source) || "Manual".equals(event.source) || "Setup".equals(event.source) || "Account".equals(event.source) || "Diagnostic".equals(event.source)) {
            return event.title;
        }
        if ("Private".equals(event.source)) {
            return friendly;
        }
        if (AppNameResolver.looksLikePackageOrClass(event.title)) {
            return friendly;
        }
        if (event.title == null || event.title.trim().isEmpty() || event.title.equals(event.packageName)) {
            return friendly;
        }
        return event.title;
    }

    private String displaySummary(TimelineEvent event) {
        if ("Private".equals(event.source)) {
            return AppNameResolver.category(event.packageName) + " 앱입니다. 세부 화면 내용은 저장하지 않았습니다.";
        }
        return event.summary == null || event.summary.trim().isEmpty()
                ? "세부 기록이 아직 없습니다."
                : AppNameResolver.scrubKnownPackageNames(event.summary);
    }

    private String activityLine(TimelineEvent event) {
        if ("Gemini".equals(event.source)) {
            return "오늘 정리";
        }
        if ("Private".equals(event.source)) {
            return "세부 내용 숨김 · " + AppNameResolver.category(event.packageName);
        }
        String summary = displaySummary(event);
        String category = AppNameResolver.category(event.packageName);
        if (summary.contains(category)) {
            return summary;
        }
        return summary + " · " + category;
    }

    private String buildPrivacyLine(TimelineEvent event) {
        if ("Private".equals(event.source) || AppNameResolver.isSensitiveCategory(event.packageName)) {
            return "민감 가능성이 있는 앱이라 앱 이름과 시간만 남기고 화면 텍스트는 숨겼습니다.";
        }
        if ("Screen".equals(event.source)) {
            return "접근성 서비스가 읽은 화면 텍스트를 로컬 PrivacyGuard가 마스킹한 뒤 저장했습니다.";
        }
        if ("UsageStats".equals(event.source)) {
            return "앱 사용 시간 기반 기록입니다. 화면 속 작업 내용은 접근성 권한이 켜져 있을 때만 보강됩니다.";
        }
        return "로컬에 저장된 정제 이벤트입니다.";
    }

    private String sourceLabel(String source) {
        if ("UsageStats".equals(source)) {
            return "앱 사용";
        }
        if ("Screen".equals(source)) {
            return "화면 흐름";
        }
        if ("Private".equals(source)) {
            return "세부 내용 숨김";
        }
        if ("Gemini".equals(source)) {
            return "오늘 정리";
        }
        if ("Manual".equals(source)) {
            return "직접 추가";
        }
        if ("Accessibility".equals(source)) {
            return "권한";
        }
        if ("Diagnostic".equals(source)) {
            return "진단";
        }
        return source;
    }

    private void switchRange(String range) {
        selectedRange = range;
        updateModeButtons();
        importUsageSnapshotForRange(range, false, true);
        refreshUi();
    }

    private void updateModeButtons() {
        setModeButton(gridModeButton, RANGE_DAY.equals(selectedRange));
        setModeButton(focusModeButton, RANGE_WEEK.equals(selectedRange));
        setModeButton(liveModeButton, RANGE_MONTH.equals(selectedRange));
        setModeButton(yearRangeButton, RANGE_YEAR.equals(selectedRange));
    }

    private void setModeButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setBackgroundResource(active ? R.drawable.bg_primary_button : R.drawable.bg_secondary_button);
        button.setTextColor(Color.parseColor(active ? "#FFFFFF" : TEXT_PRIMARY));
        button.setTypeface(button.getTypeface(), active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private TextView buildText(String text, String color, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(android.graphics.Color.parseColor(color));
        view.setTextSize(sp);
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private boolean hasUsageAccess() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                getPackageName()
        );
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isAccessibilityEnabled() {
        String expected = getPackageName() + "/" + ActivityAccessibilityService.class.getName();
        String enabled = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        return enabled != null && enabled.toLowerCase(Locale.US).contains(expected.toLowerCase(Locale.US));
    }

    private void confirmClearTimeline() {
        new AlertDialog.Builder(this)
                .setTitle("타임라인 삭제")
                .setMessage("이 기기에 저장된 활동 기록을 모두 삭제할까요?")
                .setPositiveButton("삭제", (dialog, which) -> {
                    timelineStore.clear();
                    refreshUi();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private String formatTime(long timestamp) {
        return new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date(timestamp));
    }

    private String formatDateTime(long timestamp) {
        return new SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(new Date(timestamp));
    }

    private String formatDayLabel(long timestamp) {
        return new SimpleDateFormat("MM.dd E", Locale.KOREA).format(new Date(timestamp));
    }

    private String formatMonthLabel(long timestamp) {
        return new SimpleDateFormat("yyyy.MM", Locale.KOREA).format(new Date(timestamp));
    }

    private String formatHourRange(long start) {
        Calendar calendar = Calendar.getInstance(Locale.KOREA);
        calendar.setTimeInMillis(start);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        return String.format(Locale.KOREA, "%02d:00-%02d:59", hour, hour);
    }

    private String rangeLabel(String range) {
        if (RANGE_WEEK.equals(range)) {
            return "최근 7일";
        }
        if (RANGE_MONTH.equals(range)) {
            return "최근 30일";
        }
        if (RANGE_YEAR.equals(range)) {
            return "최근 12개월";
        }
        return "오늘";
    }

    private String bucketUnitLabel(String range) {
        if (RANGE_DAY.equals(range)) {
            return "시간";
        }
        if (RANGE_YEAR.equals(range)) {
            return "월";
        }
        return "날짜";
    }

    private String formatDuration(long durationMs) {
        long minutes = Math.max(1L, durationMs / 60000L);
        if (minutes < 60L) {
            return minutes + "분";
        }
        return (minutes / 60L) + "시간 " + (minutes % 60L) + "분";
    }

    private int durationMinutes(TimelineEvent event) {
        if (event == null || event.summary == null) {
            return 30;
        }
        String summary = event.summary;
        java.util.regex.Matcher hourMatcher = java.util.regex.Pattern.compile("(\\d+)시간").matcher(summary);
        java.util.regex.Matcher minuteMatcher = java.util.regex.Pattern.compile("(\\d+)분").matcher(summary);
        int minutes = 0;
        if (hourMatcher.find()) {
            minutes += parseInt(hourMatcher.group(1), 0) * 60;
        }
        if (minuteMatcher.find()) {
            minutes += parseInt(minuteMatcher.group(1), 0);
        }
        return minutes <= 0 ? 30 : Math.min(240, minutes);
    }

    private String formatMinutes(int minutes) {
        int safe = Math.max(0, minutes);
        if (safe < 60) {
            return safe + "분";
        }
        return (safe / 60) + "시간 " + (safe % 60) + "분";
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int getHour(long timestamp) {
        Calendar calendar = Calendar.getInstance(Locale.KOREA);
        calendar.setTimeInMillis(timestamp);
        return calendar.get(Calendar.HOUR_OF_DAY);
    }

    private long startOfCurrentDayMillis() {
        Calendar calendar = Calendar.getInstance(Locale.KOREA);
        calendar.setTimeInMillis(System.currentTimeMillis());
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    private long rangeStartMillis(String range) {
        Calendar calendar = Calendar.getInstance(Locale.KOREA);
        calendar.setTimeInMillis(startOfCurrentDayMillis());
        if (RANGE_WEEK.equals(range)) {
            calendar.add(Calendar.DAY_OF_YEAR, -6);
            return calendar.getTimeInMillis();
        }
        if (RANGE_MONTH.equals(range)) {
            calendar.add(Calendar.DAY_OF_YEAR, -29);
            return calendar.getTimeInMillis();
        }
        if (RANGE_YEAR.equals(range)) {
            calendar.set(Calendar.DAY_OF_MONTH, 1);
            calendar.add(Calendar.MONTH, -11);
            return calendar.getTimeInMillis();
        }
        return calendar.getTimeInMillis();
    }

    private int getMinHour(List<TimelineEvent> events) {
        int min = getHour(System.currentTimeMillis());
        for (TimelineEvent event : events) {
            min = Math.min(min, getHour(event.timestamp));
        }
        return min;
    }

    private int getMaxHour(List<TimelineEvent> events) {
        int max = getHour(System.currentTimeMillis());
        for (TimelineEvent event : events) {
            max = Math.max(max, getHour(event.timestamp));
        }
        return max;
    }

    private String classifyPackage(String packageName) {
        return AppNameResolver.category(packageName);
    }

    private String pickEventColor(TimelineEvent event) {
        if ("Private".equals(event.source)) {
            return "#B38600";
        }
        if ("Gemini".equals(event.source)) {
            return "#7C3AED";
        }
        if ("Screen".equals(event.source)) {
            return "#008C7A";
        }
        if ("UsageStats".equals(event.source)) {
            return BRAND_BLUE;
        }
        return TEXT_PRIMARY;
    }

    private String categoryFillColor(TimelineEvent event) {
        String category = AppNameResolver.category(event.packageName);
        if ("금융".equals(category) || "Private".equals(event.source)) {
            return "#FFF4D6";
        }
        if ("웹 탐색".equals(category)) {
            return "#FFF8E6";
        }
        if ("커뮤니케이션".equals(category)) {
            return "#E8F8F4";
        }
        if ("영상".equals(category)) {
            return "#FFF1E8";
        }
        if ("소셜".equals(category)) {
            return "#FFECEF";
        }
        if ("문서 작업".equals(category)) {
            return "#EAF8EF";
        }
        if ("Manual".equals(event.source)) {
            return "#EEF2FF";
        }
        return "#F2F6FB";
    }

    private String categoryStrokeColor(TimelineEvent event) {
        String category = AppNameResolver.category(event.packageName);
        if ("금융".equals(category) || "Private".equals(event.source)) {
            return "#FFD66B";
        }
        if ("웹 탐색".equals(category)) {
            return "#F3C15F";
        }
        if ("커뮤니케이션".equals(category)) {
            return "#8DD9C5";
        }
        if ("영상".equals(category)) {
            return "#FF9B6A";
        }
        if ("소셜".equals(category)) {
            return "#FF8A9B";
        }
        if ("문서 작업".equals(category)) {
            return "#9AD9AD";
        }
        if ("Manual".equals(event.source)) {
            return "#BAC8FF";
        }
        return "#D9E2EC";
    }

    private String sourceColor(String source) {
        if ("Private".equals(source)) {
            return "#E7C96D";
        }
        if ("Screen".equals(source)) {
            return "#78D1C3";
        }
        if ("Gemini".equals(source)) {
            return "#C78BE8";
        }
        return "#8FF5AC";
    }

    private String categoryColor(String packageName) {
        String category = AppNameResolver.category(packageName);
        if ("금융".equals(category)) {
            return "#E7C96D";
        }
        if ("웹 탐색".equals(category)) {
            return "#7EA6E8";
        }
        if ("커뮤니케이션".equals(category)) {
            return "#78D1C3";
        }
        if ("영상".equals(category)) {
            return "#C78BE8";
        }
        if ("소셜".equals(category)) {
            return "#F28B82";
        }
        return "#8FF5AC";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
