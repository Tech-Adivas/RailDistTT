package com.railway.platform.distribution.infrastructure.persistence.repository;

import com.railway.platform.distribution.infrastructure.persistence.entity.ProcessedEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEventEntity, String> {
  boolean existsByEventId(String eventId);
}
