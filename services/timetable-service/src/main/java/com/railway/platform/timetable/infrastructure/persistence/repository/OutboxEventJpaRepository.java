package com.railway.platform.timetable.infrastructure.persistence.repository;

import com.railway.platform.timetable.infrastructure.persistence.entity.OutboxEventJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

/** Spring Data JPA repository for the outbox table. Writes only; Debezium handles reads. */
public interface OutboxEventJpaRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

  /** Used by the cleanup job to purge outbox rows older than the retention period. */
  void deleteByCreatedAtBefore(Instant cutoff);
}
