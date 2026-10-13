package com.jotheca.media.storage;

import com.jotheca.common.error.ApiException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev/test object storage: a local map standing in for MinIO, exactly like
 * MockPaymentProvider stands in for Paystack. Keys are namespaced per tenant by the caller,
 * so this fake cannot leak across tenants any more than a bucket can.
 */
public class InMemoryStorageService implements StorageService {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, bytes.clone());
    }

    @Override
    public byte[] read(String key) {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw ApiException.notFound("MEDIA_NOT_FOUND", "Media bytes not found");
        }
        return bytes.clone();
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }
}
