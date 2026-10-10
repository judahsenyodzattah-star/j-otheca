package com.jotheca.media.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Picks the object-storage implementation from config, never from client input.
 * Default is the in-memory dev store so the app boots anywhere (sandbox, CI).
 */
@Configuration
public class MediaStorageConfig {

    @Bean
    StorageService storageService(
            @Value("${app.storage.provider:memory}") String provider,
            @Value("${app.storage.endpoint:http://localhost:9000}") String endpoint,
            @Value("${app.storage.access-key:jotheca}") String accessKey,
            @Value("${app.storage.secret-key:jotheca-dev}") String secretKey,
            @Value("${app.storage.bucket:jotheca-media}") String bucket) {
        if ("minio".equalsIgnoreCase(provider)) {
            return new MinioStorageService(endpoint, accessKey, secretKey, bucket);
        }
        return new InMemoryStorageService();
    }
}
