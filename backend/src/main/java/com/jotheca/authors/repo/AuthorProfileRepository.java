package com.jotheca.authors.repo;

import com.jotheca.authors.model.AuthorProfile;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorProfileRepository extends JpaRepository<AuthorProfile, String> {

    Optional<AuthorProfile> findByUserId(String userId);

    Optional<AuthorProfile> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
