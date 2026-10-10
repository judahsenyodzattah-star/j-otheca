package com.jotheca.circulation.repo;

import com.jotheca.circulation.model.LoanPolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanPolicyRepository extends JpaRepository<LoanPolicy, String> {

    Optional<LoanPolicy> findByTenantId(String tenantId);
}
