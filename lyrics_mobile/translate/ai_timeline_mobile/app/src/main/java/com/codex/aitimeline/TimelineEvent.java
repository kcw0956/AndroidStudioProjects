package com.codex.aitimeline;

import org.json.JSONException;
import org.json.JSONObject;

public class TimelineEvent {
    public final long timestamp;
    public final String source;
    public final String packageName;
    public final String title;
    public final String summary;

    public TimelineEvent(long timestamp, String source, String packageName, String title, String summary) {
        this.timestamp = timestamp;
        this.source = clean(source);
        this.packageName = clean(packageName);
        this.title = clean(title);
        this.summary = clean(summary);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("timestamp", timestamp);
        object.put("source", source);
        object.put("packageName", packageName);
        object.put("title", title);
        object.put("summary", summary);
        return object;
    }

    public static TimelineEvent fromJson(JSONObject object) {
        return new TimelineEvent(
                object.optLong("timestamp", System.currentTimeMillis()),
                object.optString("source", ""),
                object.optString("packageName", ""),
                object.optString("title", ""),
                object.optString("summary", "")
        );
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
