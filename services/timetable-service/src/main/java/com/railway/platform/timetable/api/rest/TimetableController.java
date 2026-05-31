package com.railway.platform.timetable.api.rest;

import com.railway.platform.timetable.api.dto.request.*;
import com.railway.platform.timetable.api.dto.response.AuditLogEntryResponse;
import com.railway.platform.timetable.api.dto.response.TimetableResponse;
import com.railway.platform.timetable.application.command.*;
import com.railway.platform.timetable.application.handler.TimetableCommandHandler;
import com.railway.platform.timetable.domain.repository.TimetableRepository;
import com.railway.platform.timetable.domain.valueobject.LineId;
import com.railway.platform.timetable.domain.valueobject.TimetableId;
import com.railway.platform.timetable.infrastructure.persistence.repository.AuditLogJpaRepository;
import com.railway.platform.common.error.ErrorCodes;
import com.railway.platform.common.exception.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * REST controller for timetable write and limited read operations.
 *
 * <p>Actor is always extracted from the JWT principal — never from the request body.
 * All three outcome paths are handled per endpoint: success (2xx), domain failure (4xx via
 * GlobalExceptionHandler), unexpected error (500 via GlobalExceptionHandler).
 */
@RestController
@RequestMapping("/api/v1/timetables")
public class TimetableController {

  private final TimetableCommandHandler commandHandler;
  private final TimetableRepository repository;
  private final AuditLogJpaRepository auditLogRepository;

  public TimetableController(
      TimetableCommandHandler commandHandler,
      TimetableRepository repository,
      AuditLogJpaRepository auditLogRepository) {
    this.commandHandler = commandHandler;
    this.repository = repository;
    this.auditLogRepository = auditLogRepository;
  }

  // ── Create ──────────────────────────────────────────────────────────────────

  @PostMapping
  @PreAuthorize("hasRole('TIMETABLE_AUTHOR')")
  public ResponseEntity<TimetableResponse> create(
      @Valid @RequestBody CreateTimetableRequest req,
      @AuthenticationPrincipal Jwt jwt,
      UriComponentsBuilder uriBuilder) {

    var id = commandHandler.handle(new CreateTimetableCommand(
        req.lineId(), req.name(), req.description(),
        req.effectiveDate(), req.expiryDate(), jwt.getSubject()));

    var timetable = repository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCodes.TIMETABLE_NOT_FOUND, "Timetable not found after create: " + id));
    var location = uriBuilder.path("/api/v1/timetables/{id}").buildAndExpand(id).toUri();
    return ResponseEntity.created(location).body(TimetableResponse.from(timetable));
  }

  // ── Update ──────────────────────────────────────────────────────────────────

  @PatchMapping("/{id}")
  @PreAuthorize("hasRole('TIMETABLE_AUTHOR')")
  public ResponseEntity<Void> update(
      @PathVariable String id,
      @Valid @RequestBody UpdateTimetableRequest req,
      @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new UpdateTimetableCommand(
        id, req.name(), req.description(), req.effectiveDate(), req.expiryDate(), jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  // ── Submit for review ───────────────────────────────────────────────────────

  @PostMapping("/{id}/submit")
  @PreAuthorize("hasRole('TIMETABLE_AUTHOR')")
  public ResponseEntity<Void> submitForReview(
      @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new SubmitForReviewCommand(id, jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  // ── Approve ─────────────────────────────────────────────────────────────────

  @PostMapping("/{id}/approve")
  @PreAuthorize("hasRole('TIMETABLE_APPROVER')")
  public ResponseEntity<Void> approve(
      @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new ApproveCommand(id, jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  // ── Reject ──────────────────────────────────────────────────────────────────

  @PostMapping("/{id}/reject")
  @PreAuthorize("hasRole('TIMETABLE_APPROVER')")
  public ResponseEntity<Void> reject(
      @PathVariable String id,
      @Valid @RequestBody RejectRequest req,
      @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new RejectCommand(id, jwt.getSubject(), req.reason()));
    return ResponseEntity.noContent().build();
  }

  // ── Request changes ─────────────────────────────────────────────────────────

  /** Returns a PENDING_REVIEW timetable to DRAFT. Reviewer must supply written feedback. */
  @PostMapping("/{id}/request-changes")
  @PreAuthorize("hasRole('TIMETABLE_APPROVER')")
  public ResponseEntity<Void> requestChanges(
      @PathVariable String id,
      @Valid @RequestBody RequestChangesRequest req,
      @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new RequestChangesCommand(id, jwt.getSubject(), req.feedback()));
    return ResponseEntity.noContent().build();
  }

  // ── Emergency activate ──────────────────────────────────────────────────────

  @PostMapping("/{id}/emergency-activate")
  @PreAuthorize("hasRole('EMERGENCY_OPERATOR')")
  public ResponseEntity<Void> emergencyActivate(
      @PathVariable String id,
      @Valid @RequestBody EmergencyActivateRequest req,
      @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new EmergencyActivateCommand(id, jwt.getSubject(), req.justification()));
    return ResponseEntity.noContent().build();
  }

  // ── Cancel ──────────────────────────────────────────────────────────────────

  @PostMapping("/{id}/cancel")
  @PreAuthorize("hasAnyRole('TIMETABLE_AUTHOR', 'TIMETABLE_APPROVER', 'ADMIN')")
  public ResponseEntity<Void> cancel(
      @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new CancelCommand(id, jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  // ── Read ─────────────────────────────────────────────────────────────────────

  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('TIMETABLE_AUTHOR', 'TIMETABLE_APPROVER', 'EMERGENCY_OPERATOR', 'ADMIN', 'READ_ONLY')")
  public ResponseEntity<TimetableResponse> getById(@PathVariable String id) {
    return repository.findById(TimetableId.of(id))
        .map(TimetableResponse::from)
        .map(ResponseEntity::ok)
        .orElseThrow(() -> new NotFoundException(ErrorCodes.TIMETABLE_NOT_FOUND, "Timetable not found: " + id));
  }

  @GetMapping
  @PreAuthorize("hasAnyRole('TIMETABLE_AUTHOR', 'TIMETABLE_APPROVER', 'EMERGENCY_OPERATOR', 'ADMIN', 'READ_ONLY')")
  public ResponseEntity<List<TimetableResponse>> listByLine(@RequestParam String lineId) {
    return ResponseEntity.ok(
        repository.findByLineId(LineId.of(lineId)).stream()
            .map(TimetableResponse::from).toList());
  }

  /**
   * Paginated, descending audit history for a timetable.
   * Includes every state transition, the responsible actor, and emergency justifications.
   */
  @GetMapping("/{id}/audit-log")
  @PreAuthorize("hasAnyRole('TIMETABLE_APPROVER', 'ADMIN')")
  public ResponseEntity<List<AuditLogEntryResponse>> getAuditLog(
      @PathVariable String id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {

    var entries = auditLogRepository
        .findByAggregateIdOrderByOccurredAtDesc(id, PageRequest.of(page, size, Sort.by("occurredAt").descending()))
        .map(AuditLogEntryResponse::from)
        .toList();
    return ResponseEntity.ok(entries);
  }
}
