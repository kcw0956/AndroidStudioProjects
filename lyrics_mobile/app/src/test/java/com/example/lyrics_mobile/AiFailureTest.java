package com.example.lyrics_mobile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import static org.junit.Assert.*;

public class AiFailureTest {
    private String error(String message, String reason) {
        JsonObject info = new JsonObject();
        info.addProperty("reason", reason);
        JsonArray details = new JsonArray();
        details.add(info);
        JsonObject error = new JsonObject();
        error.addProperty("message", message);
        error.add("details", details);
        JsonObject root = new JsonObject();
        root.add("error", error);
        return root.toString();
    }

    @Test public void expiredKeyIsNotMisreportedAsBadRequest() {
        assertEquals("KEY_EXPIRED", AiFailure.http(400, error("API key expired", "API_KEY_EXPIRED")).code);
    }

    @Test public void leakedKeyIsNotMisreportedAsBilling() {
        assertEquals("KEY_INVALID", AiFailure.http(403, error("Your API key was reported as leaked", "")).code);
    }

    @Test public void quotaAloneDoesNotClaimInsufficientBalance() {
        AiFailure failure = AiFailure.http(429, error("Quota exceeded", "RESOURCE_EXHAUSTED"));
        assertEquals("QUOTA", failure.code);
        assertFalse(failure.message.contains("잔액 부족"));
    }

    @Test public void explicitBillingAndDailyQuotaAreDistinguished() {
        assertEquals("BILLING", AiFailure.http(403, error("outstanding balance", "")).code);
        assertEquals("BILLING", AiFailure.http(402, "").code);
        assertEquals("DAILY_QUOTA", AiFailure.http(429, error("GenerateRequestsPerDay limit", "")).code);
    }

    @Test public void permissionDoesNotClaimExpiration() {
        assertEquals("PERMISSION", AiFailure.http(403, error("Permission denied", "PERMISSION_DENIED")).code);
    }

    @Test public void untrustedProviderTextIsNeverShown() {
        AiFailure failure = AiFailure.http(400, error("Bad request https://example.test/?key=SECRET", ""));
        assertFalse(failure.message.contains("SECRET"));
        assertFalse(failure.message.contains("https://"));
        assertEquals("SERVER", AiFailure.http(503, "<html>gateway failure</html>").code);
    }

    @Test public void networkAndTimeoutHaveDifferentGuidance() {
        assertEquals("TIMEOUT", AiFailure.exception(new SocketTimeoutException()).code);
        assertEquals("NETWORK", AiFailure.exception(new UnknownHostException()).code);
    }

    @Test public void blockedAndTruncatedResponsesAreFailures() {
        assertEquals("RECITATION", AiFailure.response("RECITATION").code);
        assertEquals("MAX_TOKENS", AiFailure.response("MAX_TOKENS").code);
        assertEquals("LINE_MISMATCH", AiFailure.response("LINE_MISMATCH").code);
    }
}
