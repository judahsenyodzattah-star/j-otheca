package com.libranet.media.storage;

import com.libranet.common.error.ApiException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinIO/S3-backed storage for staging/prod. One bucket; tenant isolation comes from the
 * key prefix (t/{tenantId}/...) assigned by MediaService, plus the app's tenant guards
 * on the metadata row. Selected via app.storage.provider=minio.
 */
public class MinioStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketChecked;

    public MinioStorageService(String endpoint, String accessKey, String secretKey, String bucket) {
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder().bucket(bucket).object(key)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw storageError("upload", e);
        }
    }

    @Override
    public byte[] read(String key) {
        try (var stream = client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw ApiException.notFound("MEDIA_NOT_FOUND", "Media bytes not found");
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            log.warn("minio delete failed for {}: {}", key, e.getMessage());
        }
    }

    private void ensureBucket() throws Exception {
        if (!bucketChecked) {
            synchronized (this) {
                if (!bucketChecked) {
                    if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                        log.info("created minio bucket {}", bucket);
                    }
                    bucketChecked = true;
                }
            }
        }
    }

    private ApiException storageError(String op, Exception e) {
        log.error("minio {} failed: {}", op, e.getMessage());
        return new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                "STORAGE_UNAVAILABLE", "Object storage is unavailable; please retry.");
    }
}
