package com.railway.platform.distribution.infrastructure.persistence.repository;

import com.railway.platform.distribution.infrastructure.persistence.entity.DistributionTrackingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DistributionTrackingRepository
    extends JpaRepository<DistributionTrackingEntity, UUID> {

  List<DistributionTrackingEntity> findByScheduleEventId(String scheduleEventId);
  List<DistributionTrackingEntity> findByTimetableId(String timetableId);
}
