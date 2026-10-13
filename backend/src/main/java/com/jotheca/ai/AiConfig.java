package com.jotheca.ai;

import com.jotheca.ai.embed.EmbeddingProvider;
import com.jotheca.ai.embed.LocalHashEmbedder;
import com.jotheca.ai.embed.OpenAiEmbedder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the active embedding backend (local default; openai when configured). */
@Configuration
public class AiConfig {

    @Bean
    @org.springframework.context.annotation.Primary
    public EmbeddingProvider embeddingProvider(@Value("${app.ai.embedder:local}") String backend,
                                               LocalHashEmbedder local, OpenAiEmbedder openai) {
        return "openai".equalsIgnoreCase(backend) ? openai : local;
    }
}
