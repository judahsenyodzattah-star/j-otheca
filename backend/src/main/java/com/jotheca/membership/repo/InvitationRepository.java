package com.jotheca.membership.repo;

import com.jotheca.membership.model.Invitation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, String> {
    Optional<Invitation> findBySlug(String slug);
    Optional<Invitation> findByCode(String code);
    List<Invitation> findByTenantId(String tenantId);
    boolean existsBySlug(String slug);
    boolean existsByCode(String code);
}
