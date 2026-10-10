package com.jotheca.common.tenant;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;

/** Human-facing codes. Crockford-style alphabet avoids visually ambiguous characters (0/O, 1/I/L). */
public final class CodeGenerator {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CodeGenerator() {}

    private static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /** Permanent internal library identifier, e.g. LIB-H92KD8. */
    public static String libraryUid() { return "LIB-" + random(6); }

    /** Per-library membership/card number, e.g. MBR-7K2P9Q. */
    public static String membershipNumber() { return "MBR-" + random(6); }

    /** Short invitation code for manual entry, e.g. ASH-8T4Q. */
    public static String invitationCode() { return random(3) + "-" + random(4); }

    /** URL-safe slug candidate from a name. */
    public static String slugify(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isBlank() ? "library-" + random(4).toLowerCase(Locale.ROOT) : n;
    }

    public static String randomToken(int length) { return random(length); }
}
