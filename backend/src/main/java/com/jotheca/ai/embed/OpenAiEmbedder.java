package com.jotheca.ai.embed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Production embedder: OpenAI-compatible embeddings endpoint (text-embedding-3-small default).
 * Selected with app.ai.embedder=openai; the API key comes ONLY from the environment —
 * never config files, never logs, never audit (spec: no secrets anywhere).
 *
 * A live key is absent in dev/CI, so this impl is exercised through contract tests of the
 * port rather than the network; the local embedder stays the sandbox default.
 */
@Component
public class OpenAiEmbedder implements EmbeddingProvider {

    private final String apiKey;
    private final String model;
    private final int dims;
    private final RestClient http;

    public OpenAiEmbedder(@Value("${AI_OPENAI_API_KEY:}") String apiKey,
                          @Value("${app.ai.openai-model:text-embedding-3-small}") String model,
                          @Value("${app.ai.openai-dims:1536}") int dims,
                          @Value("${app.ai.openai-base-url:https://api.openai.com/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.dims = dims;
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String modelName() {
        return "openai:" + model;
    }

    @Override
    public int dims() {
        return dims;
    }

    @Override
    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("openai embedder selected but AI_OPENAI_API_KEY is not set");
        }
        record Request(String model, String input, Integer dimensions) {}
        record Embedding(float[] embedding) {}
        record Response(java.util.List<Embedding> data) {}
        Response response = http.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .body(new Request(model, text == null ? "" : text, dims))
                .retrieve()
                .body(Response.class);
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new IllegalStateException("empty embedding response from provider");
        }
        return response.data().get(0).embedding();
    }
}
