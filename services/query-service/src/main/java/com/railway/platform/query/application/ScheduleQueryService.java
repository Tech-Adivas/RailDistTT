package com.railway.platform.query.application;

import com.railway.platform.common.error.ErrorCodes;
import com.railway.platform.query.api.dto.ScheduleView;
import com.railway.platform.query.infrastructure.persistence.repository.ScheduleReadModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Application service for schedule read queries.
 */
@Service
@Transactional(readOnly = true)
public class ScheduleQueryService {

  private final ScheduleReadModelRepository repository;

  public ScheduleQueryService(ScheduleReadModelRepository repository) {
    this.repository = repository;
  }

  /**
   * Returns the latest computed schedule for a given timetable.
   *
   * @throws NoSuchElementException if no schedule has been computed yet.
   */
  public ScheduleView getForTimetable(String timetableId) {
    return repository.findTopByTimetableIdOrderByComputedAtDesc(timetableId)
        .map(ScheduleView::from)
        .orElseThrow(() -> new NoSuchElementException(
            ErrorCodes.TIMETABLE_NOT_FOUND + ": no schedule for timetable " + timetableId));
  }

  /**
   * Returns all active schedules for a railway line on the given date.
   * A schedule is active if effectiveDate <= date AND (expiryDate IS NULL OR expiryDate >= date).
   */
  public List<ScheduleView> getActiveSchedulesForLine(String lineId, LocalDate date) {
    return repository.findActiveSchedulesForLine(lineId, date)
        .stream()
        .map(ScheduleView::from)
        .toList();
  }
}
