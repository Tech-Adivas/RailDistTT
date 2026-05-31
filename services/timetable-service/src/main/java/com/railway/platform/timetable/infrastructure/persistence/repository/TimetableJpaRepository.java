package com.railway.platform.timetable.infrastructure.persistence.repository;

import com.railway.platform.timetable.domain.valueobject.TimetableStatus;
import com.railway.platform.timetable.infrastructure.persistence.entity.TimetableJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA repository for TimetableJpaEntity. */
public interface TimetableJpaRepository extends JpaRepository<TimetableJpaEntity, UUID> {

  List<TimetableJpaEntity> findByLineIdOrderByEffectiveDateDesc(String lineId);

  List<TimetableJpaEntity> findByStatus(TimetableStatus status);

  List<TimetableJpaEntity> findByLineIdAndStatusIn(String lineId, List<TimetableStatus> statuses);

  @Query("""
      SELECT t FROM TimetableJpaEntity t
      WHERE t.lineId = :lineId
        AND t.status IN ('ACTIVE', 'EMERGENCY_ACTIVE')
        AND t.effectiveDate <= :date
        AND (t.expiryDate IS NULL OR t.expiryDate >= :date)
      ORDER BY t.effectiveDate DESC
      """)
  Optional<TimetableJpaEntity> findActiveForLineOnDate(
      @Param("lineId") String lineId, @Param("date") LocalDate date);
}
