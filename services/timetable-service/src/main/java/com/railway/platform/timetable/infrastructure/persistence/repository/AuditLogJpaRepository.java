package com.railway.platform.timetable.infrastructure.persistence.repository;

import com.railway.platform.timetable.infrastructure.persistence.entity.AuditLogJpaEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data JPA repository for the audit log. Append-only — no delete methods exposed. */
public interface AuditLogJpaRepository extends JpaRepository<AuditLogJpaEntity, UUID> {

  /** Paginated audit history for a given aggregate. Most recent first. */
  Page<AuditLogJpaEntity> findByAggregateIdOrderByOccurredAtDesc(String aggregateId, Pageable pageable);
}
