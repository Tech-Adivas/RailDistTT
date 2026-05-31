package com.railway.platform.timetable.infrastructure.persistence.repository;

import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.repository.TimetableRepository;
import com.railway.platform.timetable.domain.valueobject.LineId;
import com.railway.platform.timetable.domain.valueobject.TimetableId;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;
import com.railway.platform.timetable.infrastructure.persistence.entity.TimetableJpaEntity;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter between the domain repository interface and Spring Data JPA.
 *
 * <p>Translates between the domain aggregate ({@link Timetable}) and the JPA entity
 * ({@link TimetableJpaEntity}). The domain layer sees only the repository interface;
 * it has no knowledge of JPA.
 */
@Repository
public class TimetableRepositoryAdapter implements TimetableRepository {

  private final TimetableJpaRepository jpaRepository;

  public TimetableRepositoryAdapter(TimetableJpaRepository jpaRepository) {
    this.jpaRepository = jpaRepository;
  }

  @Override
  public Timetable save(Timetable timetable) {
    var entity = toEntity(timetable);
    var saved = jpaRepository.save(entity);
    return toDomain(saved);
  }

  @Override
  public Optional<Timetable> findById(TimetableId id) {
    return jpaRepository.findById(id.value()).map(this::toDomain);
  }

  @Override
  public List<Timetable> findByLineId(LineId lineId) {
    return jpaRepository.findByLineIdOrderByEffectiveDateDesc(lineId.value())
        .stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<Timetable> findActiveForLineOnDate(LineId lineId, LocalDate date) {
    return jpaRepository.findActiveForLineOnDate(lineId.value(), date).map(this::toDomain);
  }

  @Override
  public List<Timetable> findByStatus(TimetableStatus status) {
    return jpaRepository.findByStatus(status).stream().map(this::toDomain).toList();
  }

  @Override
  public List<Timetable> findActiveByLineId(LineId lineId) {
    return jpaRepository
        .findByLineIdAndStatusIn(lineId.value(), List.of(TimetableStatus.ACTIVE, TimetableStatus.EMERGENCY_ACTIVE))
        .stream().map(this::toDomain).toList();
  }

  // ── Mapping ───────────────────────────────────────────────────────────────

  private TimetableJpaEntity toEntity(Timetable t) {
    var e = new TimetableJpaEntity();
    e.setId(t.getId().value());
    e.setLineId(t.getLineId().value());
    e.setStatus(t.getStatus());
    e.setName(t.getName());
    e.setDescription(t.getDescription());
    e.setEffectiveDate(t.getEffectiveDate());
    e.setExpiryDate(t.getExpiryDate());
    e.setAuthorId(t.getAuthorId());
    e.setReviewerId(t.getReviewerId());
    e.setVersion(t.getVersion());
    return e;
  }

  private Timetable toDomain(TimetableJpaEntity e) {
    return Timetable.reconstitute(
        TimetableId.of(e.getId()),
        LineId.of(e.getLineId()),
        e.getStatus(),
        e.getName(),
        e.getDescription(),
        e.getEffectiveDate(),
        e.getExpiryDate(),
        e.getAuthorId(),
        e.getReviewerId(),
        e.getVersion());
  }
}
