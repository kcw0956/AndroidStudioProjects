package com.codex.aitimeline;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class ActivityAccessibilityService extends AccessibilityService {
    public static final String ACTION_STATUS_CHANGED = "com.codex.aitimeline.ACCESSIBILITY_STATUS_CHANGED";
    public static final String EXTRA_CONNECTED = "connected";

    private TimelineStore store;
    private AppSettings appSettings;
    private long lastWriteAt = 0L;
    private String lastSignature = "";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        store = new TimelineStore(this);
        appSettings = new AppSettings(this);
        store.addEvent(new TimelineEvent(
                System.currentTimeMillis(),
                "Accessibility",
                getPackageName(),
                "화면 컨텍스트 관찰 시작",
                "사용자가 접근성 권한을 켜서 화면 텍스트와 앱 전환 기록이 활성화되었습니다."
        ));
        sendStatusBroadcast(true);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || store == null) {
            return;
        }
        if (appSettings != null && appSettings.isTrackingPaused()) {
            return;
        }

        String packageName = String.valueOf(event.getPackageName());
        if (getPackageName().equals(packageName)) {
            return;
        }
        if (appSettings != null && appSettings.isPackageHidden(packageName)) {
            return;
        }

        String title = AppNameResolver.friendlyName(this, packageName, event.getClassName() == null ? "화면 변경" : event.getClassName().toString());
        String visibleText = collectVisibleText(event);
        String summary = buildSummary(event.getEventType(), visibleText);
        String signature = packageName + "|" + title + "|" + summary;
        long now = System.currentTimeMillis();
        if (signature.equals(lastSignature) && now - lastWriteAt < 20000L) {
            return;
        }
        lastSignature = signature;
        lastWriteAt = now;

        store.addEvent(PrivacyGuard.sanitize(new TimelineEvent(now, "Screen", packageName, title, summary)));
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sendStatusBroadcast(false);
        return super.onUnbind(intent);
    }

    private void sendStatusBroadcast(boolean connected) {
        Intent intent = new Intent(ACTION_STATUS_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_CONNECTED, connected);
        sendBroadcast(intent);
    }

    private String collectVisibleText(AccessibilityEvent event) {
        StringBuilder builder = new StringBuilder();
        appendEventText(builder, event.getText());
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            appendNodeText(builder, source, 0);
            source.recycle();
        }
        String text = builder.toString().replaceAll("\\s+", " ").trim();
        if (text.length() > 220) {
            return text.substring(0, 220) + "...";
        }
        return text;
    }

    private void appendEventText(StringBuilder builder, List<CharSequence> values) {
        if (values == null) {
            return;
        }
        for (CharSequence value : values) {
            appendText(builder, value);
        }
    }

    private void appendNodeText(StringBuilder builder, AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 4 || builder.length() > 260) {
            return;
        }
        appendText(builder, node.getText());
        appendText(builder, node.getContentDescription());
        int childCount = Math.min(node.getChildCount(), 8);
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                appendNodeText(builder, child, depth + 1);
                child.recycle();
            }
        }
    }

    private void appendText(StringBuilder builder, CharSequence value) {
        if (value == null) {
            return;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(" · ");
        }
        builder.append(text);
    }

    private String buildSummary(int eventType, String visibleText) {
        String action;
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            action = "버튼/항목을 눌렀습니다";
        } else if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            action = "텍스트 입력 흐름이 감지되었습니다";
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            action = "앱 화면이 전환되었습니다";
        } else {
            action = "화면 내용이 바뀌었습니다";
        }
        return visibleText.isEmpty() ? action : action + ": " + visibleText;
    }
}
