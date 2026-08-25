package com.aicabinet.trade.auth;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * RFC 6238 TOTP（HMAC-SHA1、6 位、30 秒步长、±1 窗口），不引入外部依赖。
 */
@Component
public class TotpService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 20;
    private static final long STEP_SECONDS = 30;
    private static final int WINDOW = 1;
    private static final int CODE_DIGITS = 6;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public String otpauthUri(String secret, String account) {
        String label = "AI开门柜运营:" + account;
        return "otpauth://totp/" + percentEncode(label)
                + "?secret=" + secret
                + "&issuer=" + percentEncode("AI开门柜")
                + "&digits=" + CODE_DIGITS
                + "&period=" + STEP_SECONDS;
    }

    public boolean verify(String secret, String code) {
        if (secret == null || secret.isBlank()
                || code == null || !code.matches("\\d{" + CODE_DIGITS + "}")) {
            return false;
        }
        long counter = Instant.now().getEpochSecond() / STEP_SECONDS;
        for (long c = counter - WINDOW; c <= counter + WINDOW; c++) {
            if (constantTimeEquals(code, generateCode(secret, c))) {
                return true;
            }
        }
        return false;
    }

    String generateCode(String secret, long counter) {
        byte[] key = base32Decode(secret);
        byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
        byte[] hash = hmacSha1(key, msg);
        int offset = hash[hash.length - 1] & 0x0F;
        int bin = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int otp = bin % (int) Math.pow(10, CODE_DIGITS);
        return String.format("%0" + CODE_DIGITS + "d", otp);
    }

    private static byte[] hmacSha1(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(msg);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bits = 0;
        int value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    static byte[] base32Decode(String secret) {
        String s = secret.toUpperCase().replace("=", "").replace(" ", "");
        int bits = 0;
        int value = 0;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            int idx = BASE32_ALPHABET.indexOf(s.charAt(i));
            if (idx < 0) {
                throw new IllegalArgumentException("invalid base32 char: " + s.charAt(i));
            }
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                out.write((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String percentEncode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if ((v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || (v >= '0' && v <= '9')
                    || v == '-' || v == '_' || v == '.' || v == '~' || v == ':') {
                sb.append((char) v);
            } else {
                sb.append('%').append(String.format("%02X", v));
            }
        }
        return sb.toString();
    }
}
