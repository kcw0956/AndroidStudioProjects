package com.example.lyrics_mobile;

import android.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AcrCloudClient {
    private final OperationScope scope;
    public AcrCloudClient() { this(new OperationScope()); }
    public AcrCloudClient(OperationScope scope) { this.scope = scope; }
    private static final MediaType AUDIO_MEDIA_TYPE = MediaType.parse("application/octet-stream");
    private final OkHttpClient client = new OkHttpClient();

    public SongInfo identify(String host, String accessKey, String accessSecret, byte[] audioBytes) throws Exception {
        String invalid = RecognitionConfig.validate(host, accessKey, accessSecret);
        if (!invalid.isEmpty() || audioBytes == null || audioBytes.length < 44 || audioBytes.length > 1000000) throw new IOException("Invalid recognition configuration or WAV sample");
        String endpointHost = sanitizeHost(host);
        String path = "/v1/identify";
        long timestamp = System.currentTimeMillis() / 1000L;

        String signString = "POST\n" + path + "\n" + accessKey + "\naudio\n1\n" + timestamp;
        String signature = hmacSha1Base64(signString, accessSecret);

        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("access_key", accessKey)
                .addFormDataPart("sample_bytes", String.valueOf(audioBytes.length))
                .addFormDataPart("timestamp", String.valueOf(timestamp))
                .addFormDataPart("signature", signature)
                .addFormDataPart("data_type", "audio")
                .addFormDataPart("signature_version", "1")
                .addFormDataPart("sample", "sample.wav", RequestBody.create(audioBytes, AUDIO_MEDIA_TYPE))
                .build();

        Request request = new Request.Builder()
                .url(String.format(Locale.US, "https://%s%s", endpointHost, path))
                .post(body)
                .build();

        try (Response response = scope.execute(client.newCall(request))) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("ACRCloud request failed: " + response.code());
            }
            String json = response.body().string();
            return parseSongInfo(json);
        }
    }

    private SongInfo parseSongInfo(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("status")) {
            int code = root.getAsJsonObject("status").get("code").getAsInt();
            if (code == 1001) return null;
            if (code != 0) throw new IllegalStateException("ACRCloud rejected request: " + code);
        }
        JsonObject metadata = root.has("metadata") ? root.getAsJsonObject("metadata") : null;
        if (metadata == null || !metadata.has("music")) {
            return null;
        }

        JsonArray music = metadata.getAsJsonArray("music");
        if (music == null || music.size() == 0) {
            return null;
        }

        JsonObject first = music.get(0).getAsJsonObject();
        String title = first.has("title") ? first.get("title").getAsString() : "";
        String artist = "";

        if (first.has("artists") && first.get("artists").isJsonArray()) {
            JsonArray artists = first.getAsJsonArray("artists");
            if (artists.size() > 0 && artists.get(0).isJsonObject()) {
                JsonObject artistObj = artists.get(0).getAsJsonObject();
                artist = artistObj.has("name") ? artistObj.get("name").getAsString() : "";
            }
        }

        SongInfo songInfo = new SongInfo(title, artist);
        return songInfo.isValid() ? songInfo : null;
    }

    private String sanitizeHost(String host) {
        if (host == null) {
            return "";
        }
        return host.replace("https://", "").replace("http://", "").trim();
    }

    private String hmacSha1Base64(String value, String secret) throws Exception {
        Mac hmacSha1 = Mac.getInstance("HmacSHA1");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
        hmacSha1.init(keySpec);
        byte[] signatureBytes = hmacSha1.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP);
    }
}
