package com.example.lyrics_mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class ServiceFailureTest {
    @Test public void overloadStopsBeforeAlternativeSearchAndRecordsSafeDiagnostics() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(503).message("Service Unavailable").header("Retry-After", "1")
                    .body(okhttp3.ResponseBody.create(okhttp3.MediaType.get("text/html"), "untrusted error page")).build();
        }).build();
        OperationScope scope = new OperationScope();
        DataResult<?> result = new LrcLibClient(scope, client).find(new SongInfo("Private title", "Private artist"));
        assertEquals("SERVICE_503", result.failure.code);
        assertEquals(1, calls.get());
        assertTrue(result.failure.message.contains("1초"));
        assertFalse(result.failure.message.contains("untrusted"));
        assertTrue(scope.diagnostics.get(0).contains("HTTP_503"));
        assertFalse(scope.diagnostics.get(0).contains("Private"));
    }
    @Test public void overloadExplainsCauseAndResolutionWithoutClaimingMissingLyrics() {
        AiFailure failure = DataResult.service(503, "LRCLIB");
        assertEquals("SERVICE_503", failure.code);
        assertEquals(AiFailure.Action.RETRY, failure.action);
        assertTrue(failure.message.contains("과부하"));
        assertTrue(failure.message.contains("가사가 없다는 뜻은 아니"));
        assertTrue(failure.message.contains("결제할 필요가 없어요"));
        assertTrue(failure.message.contains("다시 시도"));
    }
    @Test public void gatewayAndRateLimitRemainDistinct() {
        assertTrue(DataResult.service(504, "LRCLIB").message.contains("응답 대기"));
        assertTrue(DataResult.service(429, "LRCLIB").message.contains("조회 한도"));
        assertEquals("NOT_FOUND", DataResult.missing("없음").code);
    }
    @Test public void bothThemesHaveReadableTextAndControls() {
        for (String id : new String[]{"charcoal", "orange_green"}) {
            AppPalette p = AppPalette.forId(id);
            assertTrue(contrast(p.text, p.surface) >= 4.5);
            assertTrue(contrast(p.muted, p.panel) >= 4.5);
            assertTrue(contrast(p.accent, p.onAccent) >= 4.5);
        }
        assertEquals("charcoal", AppPalette.normalizeId(null));
        assertEquals("charcoal", AppPalette.normalizeId("unknown"));
    }
    private double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
    private double luminance(int c) {
        return .2126 * channel((c >> 16) & 255) + .7152 * channel((c >> 8) & 255) + .0722 * channel(c & 255);
    }
    private double channel(int c) { double v = c / 255.0; return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); }
}
