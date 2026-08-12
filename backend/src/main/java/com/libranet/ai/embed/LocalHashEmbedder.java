package com.libranet.ai.embed;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic offline embedder (default; zero-cost, CI-friendly): signed feature hashing
 * over normalized unigrams + bigrams with light stemming, L2-normalized. It cannot match
 * a neural model — but it is stable, fast, and captures lexical semantics well enough that
 * "beginner Java books" ranks beginner-Java titles first. Production swaps in a neural
 * provider behind the same port (and CI keeps hermetic behavior).
 */
@Component
public class LocalHashEmbedder implements EmbeddingProvider {

    public static final int DIMS = 256;

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "by", "at",
            "from", "is", "are", "was", "were", "be", "been", "this", "that", "these", "those",
            "it", "its", "as", "after", "before", "since", "until", "than", "then", "when",
            "what", "which", "who", "how", "about", "into", "over", "under", "books", "book");

    @Override
    public String modelName() {
        return "local-hash-" + DIMS;
    }

    @Override
    public int dims() {
        return DIMS;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[DIMS];
        String[] tokens = normalize(text);
        for (int i = 0; i < tokens.length; i++) {
            add(v, tokens[i], 1.0f);
            if (i + 1 < tokens.length) {
                add(v, tokens[i] + "_" + tokens[i + 1], 1.35f); // bigram boost: "machine_learning"
            }
        }
        double norm = 0;
        for (float f : v) norm += f * f;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        }
        return v;
    }

    /** Token stream used by everything (docs and queries share it — comparability). */
    public static String[] normalize(String text) {
        if (text == null) return new String[0];
        String[] raw = text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        Set<String> out = new LinkedHashSet<>();
        for (String r : raw) {
            String tok = stem(r);
            if (tok.length() < 2 || STOPWORDS.contains(tok)) continue;
            out.add(tok);
        }
        return out.toArray(String[]::new);
    }

    /** Porter-lite: longest-match suffix stripping — enough for plural/tense drift in catalogs. */
    private static String stem(String token) {
        if (token.length() <= 3) return token;
        if (token.endsWith("ies") && token.length() > 4) return token.substring(0, token.length() - 3) + "y";
        if (token.endsWith("ing") && token.length() > 5) return token.substring(0, token.length() - 3);
        if (token.endsWith("es") && token.length() > 4) return token.substring(0, token.length() - 2);
        if (token.endsWith("s") && !token.endsWith("ss")) return token.substring(0, token.length() - 1);
        return token;
    }

    private static void add(float[] v, String feature, float weight) {
        long h = hash(feature);
        int idx = (int) Math.floorMod(h, v.length);
        float sign = (h & 1L << 62) == 0 ? 1f : -1f;
        v[idx] += sign * weight;
    }

    private static long hash(String s) {
        // FNV-1a 64-bit — stable across JVMs (embeddings are persisted)
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
