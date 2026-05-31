package com.railway.platform.timetable.api.rest;

import com.railway.platform.timetable.api.dto.request.*;
import com.railway.platform.timetable.api.dto.response.TimetableResponse;
import com.railway.platform.timetable.application.command.*;
import com.railway.platform.timetable.application.handler.TimetableCommandHandler;
import com.railway.platform.timetable.domain.repository.TimetableRepository;
import com.railway.platform.timetable.domain.valueobject.TimetableId;
import com.railway.platform.common.error.ErrorCodes;
import com.railway.platform.common.exception.NotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * REST controller for timetable write operations.
 *
 * <p>Every endpoint extracts the actor from the JWT principal — the actor is never supplied by the
 * client in the request body, preventing privilege escalation.
 *
 * <p>All three outcome paths are handled per endpoint:
 * <ul>
 *   <li>Success → 201/200/204 with body or Location header.
 *   <li>Validation failure → 422 (via GlobalExceptionHandler from common-lib).
 *   <li>Domain/constraint failure → appropriate 4xx (GlobalExceptionHandler).
 *   <li>Unexpected error → 500 (GlobalExceptionHandler).
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/timetables")
public class TimetableController {

  private final TimetableCommandHandler commandHandler;
  private final TimetableRepository repository;

  public TimetableController(TimetableCommandHandler commandHandler, TimetableRepository repository) {
    this.commandHandler = commandHandler;
    this.repository = repository;
  }

  /** Create a new timetable in DRAFT state. Returns 201 with Location header. */
  @PostMapping
  @PreAuthorize("hasRole('TIMETABLE_AUTHOR')")
  public ResponseEntity<TimetableResponse> create(
      @Valid @RequestBody CreateTimetableRequest req,
      @AuthenticationPrincipal Jwt jwt,
      UriComponentsBuilder uriBuilder) {

    var cmd = new CreateTimetableCommand(
        req.lineId(), req.name(), req.description(),
        req.effectiveDate(), req.expiryDate(), jwt.getSubject());

    TimetableId id = commandHandler.handle(cmd);
    var timetable = repository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCodes.TIMETABLE_NOT_FOUND, "Timetable not found after create: " + id));

    var location = uriBuilder.path("/api/v1/timetables/{id}").buildAndExpand(id).toUri();
    return ResponseEntity.created(location).body(TimetableResponse.from(timetable));
  }

  /** Update mutable fields of a DRAFT timetable. */
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

  /** Submit a DRAFT timetable for review. */
  @PostMapping("/{id}/submit")
  @PreAuthorize("hasRole('TIMETABLE_AUTHOR')")
  public ResponseEntity<Void> submitForReview(
      @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new SubmitForReviewCommand(id, jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  /** Approve a PENDING_REVIEW timetable. Self-approval is blocked by domain invariant. */
  @PostMapping("/{id}/approve")
  @PreAuthorize("hasRole('TIMETABLE_APPROVER')")
  public ResponseEntity<Void> approve(
      @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new ApproveCommand(id, jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  /** Reject a PENDING_REVIEW timetable with a mandatory reason. */
  @PostMapping("/{id}/reject")
  @PreAuthorize("hasRole('TIMETABLE_APPROVER')")
  public ResponseEntity<Void> reject(
      @PathVariable String id,
      @Valid @RequestBody RejectRequest req,
      @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new RejectCommand(id, jwt.getSubject(), req.reason()));
    return ResponseEntity.noContent().build();
  }

  /**
   * Emergency activate — bypasses the approval workflow. Requires EMERGENCY_OPERATOR role.
   * Justification is mandatory and recorded in the immutable audit log.
   */
  @PostMapping("/{id}/emergency-activate")
  @PreAuthorize("hasRole('EMERGENCY_OPERATOR')")
  public ResponseEntity<Void> emergencyActivate(
      @PathVariable String id,
      @Valid @RequestBody EmergencyActivateRequest req,
      @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new EmergencyActivateCommand(id, jwt.getSubject(), req.justification()));
    return ResponseEntity.noContent().build();
  }

  /** Cancel a timetable before it becomes ACTIVE. */
  @PostMapping("/{id}/cancel")
  @PreAuthorize("hasAnyRole('TIMETABLE_AUTHOR', 'TIMETABLE_APPROVER', 'ADMIN')")
  public ResponseEntity<Void> cancel(
      @PathVariable String id, @AuthenticationPrincipal Jwt jwt) {

    commandHandler.handle(new CancelCommand(id, jwt.getSubject()));
    return ResponseEntity.noContent().build();
  }

  /** Get a single timetable by ID. */
  @GetMapping("/{id}")
  @PreAuthorize("hasAnyRole('TIMETABLE_AUTHOR', 'TIMETABLE_APPROVER', 'EMERGENCY_OPERATOR', 'ADMIN', 'READ_ONLY')")
  public ResponseEntity<TimetableResponse> getById(@PathVariable String id) {
    return repository.findById(TimetableId.of(id))
        .map(TimetableResponse::from)
        .map(ResponseEntity::ok)
        .orElseThrow(() -> new NotFoundException(ErrorCodes.TIMETABLE_NOT_FOUND, "Timetable not found: " + id));
  }

  /** List all timetables for a line. */
  @GetMapping
  @PreAuthorize("hasAnyRole('TIMETABLE_AUTHOR', 'TIMETABLE_APPROVER', 'EMERGENCY_OPERATOR', 'ADMIN', 'READ_ONLY')")
  public ResponseEntity<List<TimetableResponse>> listByLine(@RequestParam String lineId) {
    var timetables = repository.findByLineId(
        com.railway.platform.timetable.domain.valueobject.LineId.of(lineId))
        .stream().map(TimetableResponse::from).toList();
    return ResponseEntity.ok(timetables);
  }
}
