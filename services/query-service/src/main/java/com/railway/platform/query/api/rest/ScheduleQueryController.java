package com.railway.platform.query.api.rest;

import com.railway.platform.query.api.dto.ScheduleView;
import com.railway.platform.query.application.ScheduleQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Read-side REST API for computed schedules.
 */
@RestController
@RequestMapping("/api/v1")
public class ScheduleQueryController {

  private final ScheduleQueryService queryService;

  public ScheduleQueryController(ScheduleQueryService queryService) {
    this.queryService = queryService;
  }

  /**
   * GET /api/v1/timetables/{id}/schedule
   * Returns the latest computed schedule for a timetable.
   */
  @GetMapping("/timetables/{id}/schedule")
  public ResponseEntity<ScheduleView> getSchedule(@PathVariable String id) {
    try {
      return ResponseEntity.ok(queryService.getForTimetable(id));
    } catch (NoSuchElementException e) {
      return ResponseEntity.notFound().build();
    }
  }

  /**
   * GET /api/v1/schedules/active?lineId={lineId}&date={date}
   * Returns all active schedules for a railway line on the given date.
   * Date format: ISO-8601 (yyyy-MM-dd).
   */
  @GetMapping("/schedules/active")
  public ResponseEntity<List<ScheduleView>> getActiveSchedules(
      @RequestParam String lineId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return ResponseEntity.ok(queryService.getActiveSchedulesForLine(lineId, date));
  }
}
