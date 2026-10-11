package dev.yesplaymusic.probe;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The two request formats used by the NetEase client embedded in YesPlayMusic. */
final class NetEaseCrypto {
    private static final String WEAPI_NONCE = "0CoJUm6Qyw8W8jud";
    private static final String WEAPI_IV = "0102030405060708";
    private static final String EAPI_KEY = "e82ckenh8dichen8";
    private static final String PUBLIC_KEY_B64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    private static final char[] SECRET_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private NetEaseCrypto() {
    }

    static JSONObject encryptWeApi(JSONObject parameters) throws Exception {
        String secret = randomSecret();
        JSONObject result = new JSONObject();
        result.put("params", encryptWeApiText(parameters.toString(), secret));
        result.put("encSecKey", encryptSecret(secret));
        return result;
    }

    static String encryptWeApiText(String json, String secret) throws Exception {
        String first = aesCbcBase64(json, WEAPI_NONCE, WEAPI_IV);
        return aesCbcBase64(first, secret, WEAPI_IV);
    }

    static String encryptSecret(String secret) throws Exception {
        if (secret == null || secret.length() != 16) {
            throw new IllegalArgumentException("WeAPI secret key must contain 16 characters");
        }
        byte[] encodedKey = Base64.getDecoder().decode(PUBLIC_KEY_B64);
        java.security.PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(encodedKey));
        byte[] reversed = new StringBuilder(secret).reverse().toString()
                .getBytes(StandardCharsets.US_ASCII);
        byte[] block = new byte[128];
        System.arraycopy(reversed, 0, block, block.length - reversed.length, reversed.length);
        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return toHex(cipher.doFinal(block));
    }

    static String encryptEApi(String apiPath, JSONObject parameters) throws Exception {
        return encryptEApiText(apiPath, parameters.toString());
    }

    /**
     * The upstream EAPI client signs the endpoint payload together with its
     * device/session envelope. Keep this separate from the cipher so it can
     * be verified without Android runtime or account data.
     */
    static JSONObject withEapiHeader(JSONObject endpointParameters, JSONObject header) throws Exception {
        return new JSONObject(withEapiHeaderJson(endpointParameters.toString(), header.toString()));
    }

    static String withEapiHeaderJson(String endpointParametersJson, String headerJson) {
        String endpoint = endpointParametersJson == null ? "" : endpointParametersJson.trim();
        String header = headerJson == null ? "" : headerJson.trim();
        if (!endpoint.startsWith("{") || !endpoint.endsWith("}")
                || !header.startsWith("{") || !header.endsWith("}")) {
            throw new IllegalArgumentException("EAPI parameters and header must be JSON objects");
        }
        String members = endpoint.substring(1, endpoint.length() - 1);
        return "{" + members + (members.isEmpty() ? "" : ",")
                + "\"header\":" + header + ",\"e_r\":false}";
    }

    static String encryptEApiText(String apiPath, String json) throws Exception {
        String path = normalizeApiPath(apiPath);
        String signature = md5("nobody" + path + "use" + json + "md5forencrypt");
        String plaintext = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + signature;
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(EAPI_KEY.getBytes(StandardCharsets.US_ASCII), "AES"));
        return toHex(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    static String normalizeApiPath(String path) {
        if (path == null || path.length() == 0) {
            throw new IllegalArgumentException("API path is required");
        }
        return path.startsWith("/api/") ? path : "/api/" + (path.startsWith("/") ? path.substring(1) : path);
    }

    private static String aesCbcBase64(String plaintext, String key, String iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    }

    private static String randomSecret() {
        char[] result = new char[16];
        for (int i = 0; i < result.length; i++) {
            result[i] = SECRET_ALPHABET[RANDOM.nextInt(SECRET_ALPHABET.length)];
        }
        return new String(result);
    }

    private static String md5(String value) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            result.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        }
        return result.toString();
    }

    private static String toHex(byte[] value) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] result = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            int current = value[i] & 0xff;
            result[i * 2] = digits[current >>> 4];
            result[i * 2 + 1] = digits[current & 0x0f];
        }
        return new String(result);
    }

    static String md5Text(String value) throws Exception {
        return md5(value);
    }
}
