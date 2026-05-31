package com.railway.platform.notification.infrastructure.persistence.repository;

import com.railway.platform.notification.infrastructure.persistence.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {
  boolean existsByEventId(String eventId);

  int deleteByProcessedAtBefore(Instant cutoff);
}
