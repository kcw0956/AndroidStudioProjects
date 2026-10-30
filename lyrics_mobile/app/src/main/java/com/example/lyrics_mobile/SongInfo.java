package com.example.lyrics_mobile;

import java.util.Locale;

public class SongInfo {
    private String title;
    private String artist;
    private String album = "";
    private String catalogId = "";
    private String appleUrl = "";
    private long durationMs;
    public SongInfo(String title, String artist) {
        this.title = title == null ? "" : title.trim();
        this.artist = artist == null ? "" : artist.trim();
    }
    public SongInfo(String title, String artist, String album, String id, String url, long duration) {
        this(title, artist);
        this.album = album == null ? "" : album;
        this.catalogId = id == null ? "" : id;
        this.appleUrl = url == null ? "" : url;
        this.durationMs = Math.max(0, duration);
    }
    public String getTitle() { return title == null ? "" : title; }
    public String getArtist() { return artist == null ? "" : artist; }
    public String getAlbum() { return album == null ? "" : album; }
    public String getCatalogId() { return catalogId == null ? "" : catalogId; }
    public String getAppleUrl() { return appleUrl == null ? "" : appleUrl; }
    public long getDurationMs() { return durationMs; }
    public boolean isValid() { return !getTitle().trim().isEmpty(); }
    public String identity() {
        return getCatalogId().isEmpty() ? getArtist().trim().toLowerCase(Locale.ROOT) + "\n"
                + getTitle().trim().toLowerCase(Locale.ROOT) + "\n" + durationMs : "itunes:" + getCatalogId();
    }
}
