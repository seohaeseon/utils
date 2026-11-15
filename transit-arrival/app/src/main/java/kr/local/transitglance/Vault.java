package kr.local.transitglance;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 기기 내 저장 데이터(인증키, 등록 정류장, 설정, 마지막 화면)를 모두 암호화해서 보관한다.
 * 암호화 키는 Android Keystore 안에서 생성되어 밖으로 꺼낼 수 없으므로,
 * 저장 파일이 유출·백업·복사되어도 다른 기기에서는 복호화할 수 없다.
 * 항목 이름을 AAD로 묶어 암호문을 다른 항목 자리에 바꿔 끼우는 것도 막는다.
 */
final class Vault {
    private Vault() {}

    private static final String ALIAS = "transitglance_master_v1";
    private static final String PREFS = "vault";

    static synchronized void put(Context c, String name, String value) {
        SharedPreferences.Editor ed = prefs(c).edit();
        if (value == null) {
            ed.remove(name).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] blob = new byte[1 + iv.length + ct.length];
            blob[0] = (byte) iv.length;
            System.arraycopy(iv, 0, blob, 1, iv.length);
            System.arraycopy(ct, 0, blob, 1 + iv.length, ct.length);
            ed.putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            // 암호화에 실패하면 평문으로 저장하지 않고 항목을 지운다.
            ed.remove(name).apply();
        }
    }

    static synchronized String get(Context c, String name) {
        String b64 = prefs(c).getString(name, null);
        if (b64 == null) return null;
        try {
            byte[] blob = Base64.decode(b64, Base64.NO_WRAP);
            int ivLen = blob[0];
            if (ivLen < 12 || ivLen > 16 || blob.length < 1 + ivLen + 16) throw new IllegalStateException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, blob, 1, ivLen));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(blob, 1 + ivLen, blob.length - 1 - ivLen), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 변조되었거나 키가 사라진 데이터는 폐기한다.
            prefs(c).edit().remove(name).apply();
            return null;
        }
    }

    /** 저장 데이터와 암호화 키를 모두 파기한다. 삭제는 즉시 디스크에 반영되어야 하므로 commit()을 쓴다. */
    @android.annotation.SuppressLint("ApplySharedPref")
    static synchronized void wipe(Context c) {
        prefs(c).edit().clear().commit();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(ALIAS);
        } catch (Exception ignored) {
            // 키 삭제에 실패해도 암호문은 이미 지워졌다.
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        java.security.Key k = ks.getKey(ALIAS, null);
        if (k instanceof SecretKey) return (SecretKey) k;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }
}
