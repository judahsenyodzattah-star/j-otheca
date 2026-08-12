package com.libranet.ai.embed;

/**
 * Embedding port (Phase 10). Implementations turn text into a fixed-dimension, L2-normalized
 * float vector. Default: {@link LocalHashEmbedder} (deterministic, offline, free — CI/dev).
 * Production can switch to {@link OpenAiEmbedder} via app.ai.embedder=openai + AI_OPENAI_API_KEY.
 */
public interface EmbeddingProvider {

    /** Stable identity stored on every embedding row (model churn → stale detection). */
    String modelName();

    int dims();

    /** Embed one text; the vector MUST be L2-normalized so cosine == dot product. */
    float[] embed(String text);

    /** Serialize as a pgvector literal ([0.1,-0.2,...]) — valid both as TEXT and `::vector`. */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(v[i]));
        }
        return sb.append(']').toString();
    }

    /** Parse a literal produced by {@link #toVectorLiteral}. */
    static float[] fromVectorLiteral(String literal) {
        String body = literal.startsWith("[") && literal.endsWith("]")
                ? literal.substring(1, literal.length() - 1) : literal;
        if (body.isBlank()) return new float[0];
        String[] parts = body.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) v[i] = Float.parseFloat(parts[i].trim());
        return v;
    }
}
