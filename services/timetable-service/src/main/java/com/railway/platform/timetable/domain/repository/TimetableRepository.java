package com.railway.platform.timetable.domain.repository;

import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.valueobject.LineId;
import com.railway.platform.timetable.domain.valueobject.TimetableId;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Domain repository interface for the Timetable aggregate.
 *
 * <p>The domain layer depends on this interface; the infrastructure layer provides the JPA
 * implementation. This inversion keeps the domain free of persistence framework concerns.
 *
 * <p>All methods that load a Timetable for mutation must use pessimistic or optimistic locking.
 * The JPA implementation uses @Version for optimistic locking on save.
 */
public interface TimetableRepository {

  /** Persist a new or updated timetable. Throws on optimistic lock conflict. */
  Timetable save(Timetable timetable);

  /** Find by ID. Returns empty if not found. */
  Optional<Timetable> findById(TimetableId id);

  /** Find all timetables for a line, ordered by effective date descending. */
  List<Timetable> findByLineId(LineId lineId);

  /**
   * Find the currently ACTIVE timetable for a line on a specific date.
   * Returns empty if no active timetable covers that date.
   */
  Optional<Timetable> findActiveForLineOnDate(LineId lineId, LocalDate date);

  /** Find all timetables in a given status — used by activation and supersession jobs. */
  List<Timetable> findByStatus(TimetableStatus status);

  /**
   * Find all ACTIVE timetables for a line (there should normally be at most one, but
   * the system must handle overlap during activation to correctly supersede the old one).
   */
  List<Timetable> findActiveByLineId(LineId lineId);

  /**
   * Find all timetables for a line whose status is in the supplied set.
   *
   * <p>Used by the single-active-timetable conflict detection logic to locate any
   * currently ACTIVE or EMERGENCY_ACTIVE timetable before activating a new one,
   * so it can be atomically superseded within the same transaction.
   */
  List<Timetable> findByLineIdAndStatusIn(LineId lineId, List<TimetableStatus> statuses);
}
