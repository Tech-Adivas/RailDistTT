package com.railway.platform.query.api.rest;

import com.railway.platform.query.api.dto.TimetableView;
import com.railway.platform.query.application.TimetableQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Read-side REST API for timetables.
 *
 * <p>All endpoints are read-only (GET). No state mutations happen here — this is the
 * query side of the CQRS split. The write side is in timetable-service.
 */
@RestController
@RequestMapping("/api/v1")
public class TimetableQueryController {

  private final TimetableQueryService queryService;

  public TimetableQueryController(TimetableQueryService queryService) {
    this.queryService = queryService;
  }

  /**
   * GET /api/v1/timetables/{id}
   * Returns the current read model state for a single timetable.
   */
  @GetMapping("/timetables/{id}")
  public ResponseEntity<TimetableView> getTimetable(@PathVariable String id) {
    try {
      return ResponseEntity.ok(queryService.getById(id));
    } catch (NoSuchElementException e) {
      return ResponseEntity.notFound().build();
    }
  }

  /**
   * GET /api/v1/lines/{lineId}/timetables?status={status}
   * Lists timetables for a railway line. Optional {@code status} filter.
   */
  @GetMapping("/lines/{lineId}/timetables")
  public ResponseEntity<List<TimetableView>> listTimetables(
      @PathVariable String lineId,
      @RequestParam(required = false) String status) {
    return ResponseEntity.ok(queryService.listByLine(lineId, status));
  }
}
