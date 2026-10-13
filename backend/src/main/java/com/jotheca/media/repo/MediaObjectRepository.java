package com.jotheca.media.repo;

import com.jotheca.media.model.MediaObject;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaObjectRepository extends JpaRepository<MediaObject, String> {

    /** Bytes currently counting against the tenant's storage quota (ACTIVE media only). */
    @Query("select coalesce(sum(m.sizeBytes), 0) from MediaObject m"
            + " where m.tenantId = :tenantId and m.status = 'ACTIVE'")
    long activeBytes(@Param("tenantId") String tenantId);

    Optional<MediaObject> findByObjectKey(String objectKey);
}
