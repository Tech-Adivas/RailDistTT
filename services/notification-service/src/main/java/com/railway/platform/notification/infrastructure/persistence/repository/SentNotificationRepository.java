package com.railway.platform.notification.infrastructure.persistence.repository;

import com.railway.platform.notification.infrastructure.persistence.entity.SentNotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SentNotificationRepository extends JpaRepository<SentNotificationEntity, UUID> {
  List<SentNotificationEntity> findByEventId(String eventId);
}
