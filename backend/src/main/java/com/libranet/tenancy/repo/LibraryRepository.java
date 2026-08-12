package com.libranet.tenancy.repo;

import com.libranet.tenancy.model.Library;
import com.libranet.tenancy.model.LibraryStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LibraryRepository extends JpaRepository<Library, String>, JpaSpecificationExecutor<Library> {
    Optional<Library> findByUid(String uid);
    Optional<Library> findBySlug(String slug);
    boolean existsBySlug(String slug);
    boolean existsByUid(String uid);
    /** Platform analytics. */
    long countByStatus(LibraryStatus status);

}
