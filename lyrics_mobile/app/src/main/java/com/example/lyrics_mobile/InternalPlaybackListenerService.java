package com.example.lyrics_mobile;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.List;

public class InternalPlaybackListenerService extends NotificationListenerService {
    public interface PlaybackListener { void onSongChanged(SongInfo song); }
    private static volatile PlaybackListener listener;
    private static volatile SongInfo lastSong;
    private static InternalPlaybackListenerService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();
    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { publish(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { publish(); }
        @Override public void onSessionDestroyed() { refresh(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = ignored -> refresh();
    public static void setPlaybackListener(PlaybackListener value) { listener = value; }
    public static SongInfo getLastSongInfo() { return lastSong; }
    public static void requestRefresh() { if (instance != null) instance.handler.post(instance::refresh); }
    @Override public void onListenerConnected() {
        instance = this;
        MediaSessionManager manager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        try { manager.addOnActiveSessionsChangedListener(sessions, new ComponentName(this, getClass()), handler); }
        catch (SecurityException ignored) { }
        refresh();
    }
    private void refresh() {
        for (MediaController controller : controllers) controller.unregisterCallback(callback);
        controllers.clear();
        try {
            MediaSessionManager manager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            controllers.addAll(manager.getActiveSessions(new ComponentName(this, getClass())));
            if (controllers.isEmpty()) {
                StatusBarNotification[] notifications = getActiveNotifications();
                if (notifications != null) for (StatusBarNotification n : notifications) {
                    android.os.Bundle extras = n.getNotification().extras;
                    MediaSession.Token token = extras == null ? null : extras.getParcelable("android.mediaSession");
                    if (token != null) controllers.add(new MediaController(this, token));
                }
            }
            for (MediaController controller : controllers) controller.registerCallback(callback, handler);
        } catch (SecurityException ignored) { controllers.clear(); }
        publish();
    }
    private void publish() {
        SongInfo selected = null;
        int best = 0;
        for (MediaController controller : controllers) {
            PlaybackState state = controller.getPlaybackState();
            MediaMetadata metadata = controller.getMetadata();
            if (state == null || metadata == null) continue;
            int rank = state.getState() == PlaybackState.STATE_PLAYING ? 2 : state.getState() == PlaybackState.STATE_PAUSED ? 1 : 0;
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (title == null || title.trim().isEmpty() || rank <= best) continue;
            selected = new SongInfo(title, artist, metadata.getString(MediaMetadata.METADATA_KEY_ALBUM), "", "",
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            best = rank;
        }
        SongInfo previous = lastSong;
        boolean changed = previous == null ? selected != null : selected == null || !previous.identity().equals(selected.identity());
        lastSong = selected;
        PlaybackListener target = listener;
        if (target != null && changed) target.onSongChanged(selected);
    }
    @Override public void onNotificationPosted(StatusBarNotification n) { handler.post(this::refresh); }
    @Override public void onNotificationRemoved(StatusBarNotification n) { handler.post(this::refresh); }
    @Override public void onListenerDisconnected() { cleanup(); }
    @Override public void onDestroy() { cleanup(); super.onDestroy(); }
    private void cleanup() {
        try { ((MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE)).removeOnActiveSessionsChangedListener(sessions); }
        catch (RuntimeException ignored) { }
        for (MediaController controller : controllers) controller.unregisterCallback(callback);
        controllers.clear(); instance = null; lastSong = null;
        if (listener != null) listener.onSongChanged(null);
    }
}
