package dev.yesplaymusic.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Provider sessions stay encrypted in app-private storage. Never exposed to the UI bridge. */
public final class AuthStore {
    private static final Object LOCK = new Object();
    private static final String ALIAS = "yesplaymusic.provider.sessions.v1";
    private final SharedPreferences preferences;
    public AuthStore(Context context) { preferences = context.getApplicationContext().getSharedPreferences("auth_private", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    public String get(String provider) {
        synchronized (LOCK) {
            String value = preferences.getString(provider, ""); if (value.isEmpty()) return "";
            try {
                String[] pieces = value.split(":", 2);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)));
                return new String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), "UTF-8");
            } catch (Exception error) { return ""; }
        }
    }
    public void put(String provider, String value) {
        synchronized (LOCK) {
            if (value == null || value.isEmpty()) { clear(provider); return; }
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
                String result = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes("UTF-8")), Base64.NO_WRAP);
                if (!preferences.edit().putString(provider, result).commit()) throw new IllegalStateException("Session save failed");
            } catch (Exception error) { throw new IllegalStateException("登录信息未能安全保存，请重试", error); }
        }
    }
    public void clear(String provider) { synchronized (LOCK) { if (!preferences.edit().remove(provider).commit()) throw new IllegalStateException("退出登录未能保存，请重试"); } }
}
