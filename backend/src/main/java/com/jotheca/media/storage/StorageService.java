package com.jotheca.media.storage;

/**
 * Object-storage port (spec: media bytes live in MinIO/S3, the DB keeps metadata only).
 * Implementations: {@link InMemoryStorageService} for dev/tests (provider=memory),
 * {@link MinioStorageService} when app.storage.provider=minio.
 */
public interface StorageService {

    void put(String key, byte[] bytes, String contentType);

    byte[] read(String key);

    void delete(String key);
}
