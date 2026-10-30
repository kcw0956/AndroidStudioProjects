package com.codex.aitimeline;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TimelineStore {
    private static final String PREF_NAME = "ai_timeline_store";
    private static final String KEY_EVENTS = "events";
    private static final int MAX_EVENTS = 5000;

    private final SharedPreferences prefs;

    public TimelineStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public synchronized void addEvent(TimelineEvent event) {
        if (event == null) {
            return;
        }
        List<TimelineEvent> events = loadEvents();
        for (TimelineEvent existing : events) {
            boolean sameSource = existing.source.equals(event.source);
            boolean samePackage = existing.packageName.equals(event.packageName);
            boolean sameTitle = existing.title.equals(event.title);
            boolean sameTime = Math.abs(existing.timestamp - event.timestamp) < 2000L;
            if (sameSource && samePackage && sameTitle && sameTime) {
                return;
            }
        }
        if (!events.isEmpty()) {
            TimelineEvent latest = events.get(0);
            boolean sameSource = latest.source.equals(event.source);
            boolean samePackage = latest.packageName.equals(event.packageName);
            boolean veryRecent = Math.abs(latest.timestamp - event.timestamp) < 15000L;
            if (sameSource && samePackage && veryRecent && latest.summary.equals(event.summary)) {
                return;
            }
        }

        events.add(0, event);
        while (events.size() > MAX_EVENTS) {
            events.remove(events.size() - 1);
        }
        save(events);
    }

    public synchronized List<TimelineEvent> loadEvents() {
        String raw = prefs.getString(KEY_EVENTS, "[]");
        List<TimelineEvent> events = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) {
                    events.add(TimelineEvent.fromJson(object));
                }
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return events;
    }

    public synchronized void clear() {
        prefs.edit().remove(KEY_EVENTS).apply();
    }

    private void save(List<TimelineEvent> events) {
        JSONArray array = new JSONArray();
        for (TimelineEvent event : events) {
            try {
                array.put(event.toJson());
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply();
    }
}
