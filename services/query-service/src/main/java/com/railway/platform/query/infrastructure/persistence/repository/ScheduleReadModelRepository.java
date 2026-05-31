package com.railway.platform.query.infrastructure.persistence.repository;

import com.railway.platform.query.infrastructure.persistence.entity.ScheduleReadModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduleReadModelRepository extends JpaRepository<ScheduleReadModelEntity, UUID> {

  Optional<ScheduleReadModelEntity> findTopByTimetableIdOrderByComputedAtDesc(String timetableId);

  /**
   * Returns the most recently computed schedule for a line that is active on the given date:
   * effectiveDate <= date AND (expiryDate IS NULL OR expiryDate >= date).
   */
  @Query("""
      SELECT s FROM ScheduleReadModelEntity s
       WHERE s.lineId = :lineId
         AND s.effectiveDate <= :date
         AND (s.expiryDate IS NULL OR s.expiryDate >= :date)
       ORDER BY s.effectiveDate DESC
      """)
  List<ScheduleReadModelEntity> findActiveSchedulesForLine(
      @Param("lineId") String lineId,
      @Param("date") LocalDate date);
}
