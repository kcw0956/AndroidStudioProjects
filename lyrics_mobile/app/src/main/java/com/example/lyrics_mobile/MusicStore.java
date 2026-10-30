package com.example.lyrics_mobile;

import android.content.Context;
import android.util.AtomicFile;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Atomic, versioned disk storage. Reset and writes share a lock and generation. */
public final class MusicStore {
    public static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    public static final long MONTH = 30L * 24 * 60 * 60 * 1000;
    private static final long MAX_CACHE = 20L * 1024 * 1024;
    private static final int SCHEMA = 2;
    private static final Object LOCK = new Object();
    private static long generation;
    private final File root;
    private final Gson gson = new Gson();
    public static class Visit {
        public SongInfo song;
        public long openedAt;
        public boolean saved;
        public String source;
    }
    public static class Library { public List<Visit> songs = new ArrayList<>(); }
    public static class Weekly {
        public long generatedAt;
        public List<SongInfo> songs = new ArrayList<>();
        public List<String> shown = new ArrayList<>();
    }
    public MusicStore(Context context) {
        root = new File(context.getFilesDir(), "ivlyrics_data");
        synchronized (LOCK) { root.mkdirs(); migrateLegacy(context); }
    }
    public File root() { return root; }
    public long generation() { synchronized (LOCK) { return generation; } }
    public static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format(java.util.Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String variant(SongInfo song, String lyrics, String lang, String model, String kind) {
        return song.identity() + "\n" + hash(lyrics) + "\n" + lang + "\n" + model + "\nlyrics-prompt-v4-indexed\n" + kind;
    }
    public <T> T read(String group, String key, Class<T> type, long ttl) {
        synchronized (LOCK) {
            File file = file(group, key);
            if (!file.exists() && !new File(file + ".bak").exists()) return null;
            try {
                JsonObject json = JsonParser.parseString(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8)).getAsJsonObject();
                long age = System.currentTimeMillis() - json.get("savedAt").getAsLong();
                if (json.get("schema").getAsInt() != SCHEMA || age < 0 || (ttl > 0 && age > ttl)) return null;
                return gson.fromJson(json.get("value"), type);
            } catch (Exception e) { new AtomicFile(file).delete(); return null; }
        }
    }
    public boolean write(String group, String key, Object value, long token) {
        synchronized (LOCK) {
            if (token != generation || value == null) return false;
            File file = file(group, key);
            if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) return false;
            JsonObject data = new JsonObject();
            data.addProperty("schema", SCHEMA);
            data.addProperty("savedAt", System.currentTimeMillis());
            data.add("value", gson.toJsonTree(value));
            AtomicFile atomic = new AtomicFile(file);
            FileOutputStream out = null;
            try {
                out = atomic.startWrite();
                out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                atomic.finishWrite(out);
                if (!"library".equals(group)) pruneCache();
                return true;
            } catch (Exception e) { if (out != null) atomic.failWrite(out); return false; }
        }
    }
    public Library library() {
        synchronized (LOCK) {
            Library value = read("library", "history", Library.class, 0);
            if (value == null || value.songs == null) value = new Library();
            java.util.Iterator<Visit> valid = value.songs.iterator();
            while (valid.hasNext()) { Visit v = valid.next(); if (v == null || v.song == null || !v.song.isValid()) valid.remove(); }
            return value;
        }
    }
    public boolean visit(SongInfo song, String source) {
        return visit(song, source, generation());
    }
    public boolean visit(SongInfo song, String source, long token) {
        synchronized (LOCK) {
            if (token != generation) return false;
            Library data = library();
            Visit visit = null;
            for (Visit v : data.songs) if (v.song.identity().equals(song.identity())) visit = v;
            if (visit == null) { visit = new Visit(); }
            else data.songs.remove(visit);
            visit.song = song; visit.source = source; visit.openedAt = System.currentTimeMillis();
            data.songs.add(0, visit);
            // Keep favourites; retain the most recent 200 other records.
            int recent = 0;
            java.util.Iterator<Visit> it = data.songs.iterator();
            while (it.hasNext()) { Visit v = it.next(); if (!v.saved && ++recent > 200) it.remove(); }
            return write("library", "history", data, generation);
        }
    }
    public boolean toggleSaved(SongInfo song) {
        synchronized (LOCK) {
            Library data = library();
            for (Visit v : data.songs) if (v.song.identity().equals(song.identity())) {
                v.saved = !v.saved; return write("library", "history", data, generation);
            }
            return false;
        }
    }
    public boolean isSaved(SongInfo song) {
        for (Visit v : library().songs) if (v.song.identity().equals(song.identity())) return v.saved;
        return false;
    }
    public boolean clear(boolean includeLibrary) {
        synchronized (LOCK) {
            generation++;
            boolean success = true;
            File[] dirs = root.listFiles();
            if (dirs != null) for (File dir : dirs) if (includeLibrary || !"library".equals(dir.getName())) success &= delete(dir);
            return success;
        }
    }
    public String stats() {
        synchronized (LOCK) { return library().songs.size() + "곡 기록 · " + String.format(java.util.Locale.KOREA, "%.1f MB", bytes(root) / 1048576.0); }
    }
    private File file(String group, String key) { return new File(new File(root, group), hash(key) + ".json"); }
    private long bytes(File f) {
        if (f.isFile()) return f.length();
        long n = 0; File[] children = f.listFiles();
        if (children != null) for (File child : children) n += bytes(child);
        return n;
    }
    private boolean delete(File f) {
        boolean ok = true; File[] files = f.listFiles();
        if (files != null) for (File child : files) ok &= delete(child);
        return f.delete() && ok;
    }
    private void pruneCache() {
        List<File> files = new ArrayList<>();
        File[] dirs = root.listFiles();
        long total = 0;
        if (dirs != null) for (File dir : dirs) if (!"library".equals(dir.getName())) {
            File[] items = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (items != null) for (File f : items) { files.add(f); total += f.length(); }
        }
        Collections.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long now = System.currentTimeMillis();
        for (File file : files) if (total > MAX_CACHE || now - file.lastModified() > MONTH) {
            long size = file.length(); new AtomicFile(file).delete(); total -= size;
        }
    }
    private void migrateLegacy(Context context) {
        if (context.getSharedPreferences("store_migration", 0).getBoolean("done", false)) return;
        File external = context.getExternalFilesDir(null);
        if (external == null) return;
        File[] files = new File(external, "ivlyrics_data/songs").listFiles((d, n) -> n.endsWith(".json"));
        boolean ok = true;
        if (files != null) for (File file : files) {
            try {
                JsonObject old = JsonParser.parseString(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8)).getAsJsonObject();
                SongInfo song = new SongInfo(old.get("title").getAsString(), old.get("artist").getAsString());
                JsonElement original = old.get("originalLyrics");
                if (song.isValid() && original != null && !original.isJsonNull() && !original.getAsString().trim().isEmpty()) {
                    LrcLibClient.Lyrics lyrics = new LrcLibClient.Lyrics(); lyrics.plain = original.getAsString();
                    ok &= write("lyrics", song.identity(), lyrics, generation);
                    ok &= visit(song, "이전 데이터");
                }
            } catch (Exception ignored) { /* Corrupted legacy records are not imported. */ }
        }
        if (ok) context.getSharedPreferences("store_migration", 0).edit().putBoolean("done", true).apply();
    }
}
