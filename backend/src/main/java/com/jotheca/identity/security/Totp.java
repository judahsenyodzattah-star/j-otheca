package com.jotheca.identity.security;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 TOTP (30-second steps, 6 digits, HMAC-SHA1) with a minimal RFC 4648 Base32 codec —
 * no third-party dependency. Used for MFA enrolment/verification (Phase 8).
 * The secret never leaves the server except once during enrolment; it is never logged.
 */
public final class Totp {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;

    private Totp() {}

    /** New 160-bit secret, Base32 without padding (authenticator-app friendly). */
    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** otpauth:// URI the mobile app renders as a QR code. */
    public static String otpauthUri(String issuer, String accountEmail, String secret) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(accountEmail)
                + "?secret=" + secret + "&issuer=" + urlEncode(issuer) + "&digits=" + DIGITS;
    }

    /** Verifies a 6-digit code allowing ±1 step of clock drift. */
    public static boolean verify(String base32Secret, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long step = Instant_now() / STEP_SECONDS;
        for (long skew = -1; skew <= 1; skew++) {
            if (hotp(base32Secret, step + skew).equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** Current HOTP value for a time step (also used by tests to compute the expected code). */
    public static String hotp(String base32Secret, long step) {
        try {
            byte[] key = base32Decode(base32Secret);
            byte[] counter = ByteBuffer.allocate(8).putLong(step).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(counter);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TOTP failure", e);
        }
    }

    private static long Instant_now() {
        return System.currentTimeMillis() / 1000L;
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(B32[(buffer >> (bitsLeft - 5)) & 0x1F]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(B32[(buffer << (5 - bitsLeft)) & 0x1F]);
        }
        return out.toString();
    }

    static byte[] base32Decode(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(clean.length() * 5 / 8);
        int buffer = 0, bitsLeft = 0;
        for (char c : clean.toCharArray()) {
            int v = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Invalid Base32 character: " + c);
            }
            buffer = (buffer << 5) | v;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
