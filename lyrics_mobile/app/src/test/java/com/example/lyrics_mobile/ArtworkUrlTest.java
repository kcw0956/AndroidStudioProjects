package com.example.lyrics_mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArtworkUrlTest {
    @Test public void acceptsOnlyHttpsAppleArtworkHosts() {
        for (String url : new String[]{"https://mzstatic.com/a.jpg", "https://is1-ssl.mzstatic.com/image/a.jpg",
                "https://a.b.mzstatic.com:443/a?size=100", "HTTPS://IS1.MZSTATIC.COM/a"}) {
            assertTrue(url, ArtworkUrl.isSafe(url));
        }
    }

    @Test public void rejectsUntrustedAndAmbiguousUrls() {
        for (String url : new String[]{"", "http://is1.mzstatic.com/a", "https://notmzstatic.com/a",
                "https://mzstatic.com.evil.test/a", "https://mzstatic.com@evil.test/a",
                "https://evil.test@mzstatic.com/a", "https://127.0.0.1/a", "https://[::1]/a",
                "https://localhost/a", "file:///a", "//mzstatic.com/a", "https://mzstatic.com:8443/a",
                "https://mzstatic.com./a", "https://mzstatic.com/a#fragment",
                "https://mzstatic.com\\@evil.test/a", "https://%6dzstatic.com/a", " https://mzstatic.com/a"}) {
            assertFalse(url, ArtworkUrl.isSafe(url));
        }
        assertFalse(ArtworkUrl.isSafe(null));
    }

    @Test public void artworkIsOptionalAndFluent() {
        SongInfo song = new SongInfo("Title", "Artist");
        String identity = song.identity();
        assertEquals("", song.getArtworkUrl());
        assertSame(song, song.withArtworkUrl(" https://is1.mzstatic.com/a "));
        assertEquals("https://is1.mzstatic.com/a", song.getArtworkUrl());
        assertEquals(identity, song.identity());
        assertEquals("", song.withArtworkUrl(null).getArtworkUrl());
    }
}
