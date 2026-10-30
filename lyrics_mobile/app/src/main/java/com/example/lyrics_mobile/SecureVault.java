package com.example.lyrics_mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials are encrypted with a device-bound key and excluded from backups. */
public final class SecureVault {
    private static final String ALIAS = "lyrics_credentials_v1";
    private final SharedPreferences prefs;
    public SecureVault(Context context) { prefs = context.getSharedPreferences("secure_credentials", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        if (Build.VERSION.SDK_INT < 23) throw new IllegalStateException("키 저장은 Android 6 이상에서 지원해요.");
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public void put(String name, String value) {
        if (value == null || value.trim().isEmpty()) { prefs.edit().remove(name).commit(); return; }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            String stored = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
            if (!prefs.edit().putString(name, stored).commit()) throw new java.io.IOException();
        } catch (Exception e) { throw new IllegalStateException("키를 안전하게 저장하지 못했어요. 기기 잠금 설정과 저장 공간을 확인해 주세요."); }
    }
    public String get(String name) {
        String stored = prefs.getString(name, "");
        if (stored == null || stored.isEmpty()) return "";
        try {
            String[] parts = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; } // A device-key reset requires re-entry, never a plaintext fallback.
    }
}
