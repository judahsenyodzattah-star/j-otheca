package com.libranet.issues.repo;

import com.libranet.issues.model.IssueMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IssueMessageRepository extends JpaRepository<IssueMessage, String> {

    List<IssueMessage> findByTenantIdAndIssueIdOrderByCreatedAtAsc(String tenantId, String issueId);

    long countByTenantIdAndIssueId(String tenantId, String issueId);
}
