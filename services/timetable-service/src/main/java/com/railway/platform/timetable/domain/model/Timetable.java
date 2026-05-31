package com.railway.platform.timetable.domain.model;

import com.railway.platform.common.error.ErrorCodes;
import com.railway.platform.common.exception.InvalidStateTransitionException;
import com.railway.platform.timetable.domain.event.TimetableDomainEvent;
import com.railway.platform.timetable.domain.valueobject.LineId;
import com.railway.platform.timetable.domain.valueobject.TimetableId;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The Timetable aggregate root.
 *
 * <p>All invariants are enforced here. No caller can put the aggregate into an invalid state
 * by bypassing the public methods. The aggregate is intentionally free of JPA annotations —
 * persistence concerns live in the infrastructure layer (TimetableJpaEntity).
 *
 * <p>Domain events accumulated during a command are returned to the application layer, which
 * persists them in the Transactional Outbox (see TimetableCommandHandler). The aggregate itself
 * does not write to the outbox — that coupling would violate the domain/infrastructure boundary.
 */
public class Timetable {

  private final TimetableId id;
  private final LineId lineId;
  private TimetableStatus status;
  private String name;
  private String description;
  private LocalDate effectiveDate;
  private LocalDate expiryDate; // null = open-ended
  private String authorId;      // user who created this timetable
  private String reviewerId;    // user who approved/rejected (set on review actions)
  private long version;         // optimistic lock version — must match DB @Version

  /** Domain events raised during this command — cleared after persistence. */
  private final List<TimetableDomainEvent> domainEvents = new ArrayList<>();

  // ── Factory method ──────────────────────────────────────────────────────────

  /**
   * Creates a new timetable in DRAFT state.
   *
   * @param authorId The user creating the timetable; used to block self-approval.
   */
  public static Timetable create(
      TimetableId id,
      LineId lineId,
      String name,
      String description,
      LocalDate effectiveDate,
      LocalDate expiryDate,
      String authorId) {

    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(lineId, "lineId must not be null");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    Objects.requireNonNull(effectiveDate, "effectiveDate must not be null");
    if (expiryDate != null && !expiryDate.isAfter(effectiveDate)) {
      throw new IllegalArgumentException("expiryDate must be after effectiveDate");
    }
    if (authorId == null || authorId.isBlank())
      throw new IllegalArgumentException("authorId must not be blank");

    var timetable = new Timetable(id, lineId, name, description, effectiveDate, expiryDate, authorId);
    timetable.raiseDomainEvent(TimetableDomainEvent.created(timetable, authorId));
    return timetable;
  }

  private Timetable(
      TimetableId id,
      LineId lineId,
      String name,
      String description,
      LocalDate effectiveDate,
      LocalDate expiryDate,
      String authorId) {
    this.id = id;
    this.lineId = lineId;
    this.name = name;
    this.description = description;
    this.effectiveDate = effectiveDate;
    this.expiryDate = expiryDate;
    this.authorId = authorId;
    this.status = TimetableStatus.DRAFT;
    this.version = 0;
  }

  /** Reconstitution constructor — called by the persistence mapper only. */
  public static Timetable reconstitute(
      TimetableId id,
      LineId lineId,
      TimetableStatus status,
      String name,
      String description,
      LocalDate effectiveDate,
      LocalDate expiryDate,
      String authorId,
      String reviewerId,
      long version) {

    var t = new Timetable(id, lineId, name, description, effectiveDate, expiryDate, authorId);
    t.status = status;
    t.reviewerId = reviewerId;
    t.version = version;
    return t;
  }

  // ── Commands ────────────────────────────────────────────────────────────────

  /** Updates mutable draft fields. Only allowed in DRAFT state. */
  public void update(String name, String description, LocalDate effectiveDate, LocalDate expiryDate, String actor) {
    assertStatus(TimetableStatus.DRAFT, "update");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
    if (expiryDate != null && !expiryDate.isAfter(effectiveDate)) {
      throw new IllegalArgumentException("expiryDate must be after effectiveDate");
    }
    this.name = name;
    this.description = description;
    this.effectiveDate = effectiveDate;
    this.expiryDate = expiryDate;
    raiseDomainEvent(TimetableDomainEvent.updated(this, actor));
  }

  /** Submits the timetable for review. Author cannot self-approve the resulting submission. */
  public void submitForReview(String actor) {
    ApprovalStateMachine.assertTransitionAllowed(status, TimetableStatus.PENDING_REVIEW, id.toString());
    transitionTo(TimetableStatus.PENDING_REVIEW, actor);
    raiseDomainEvent(TimetableDomainEvent.submittedForReview(this, actor));
  }

  /**
   * Approves the timetable.
   *
   * @param reviewerId The approver's user ID. Must differ from the author (no self-approval).
   * @throws InvalidStateTransitionException if the reviewer is the same as the author.
   */
  public void approve(String reviewerId) {
    ApprovalStateMachine.assertTransitionAllowed(status, TimetableStatus.APPROVED, id.toString());
    // Self-approval invariant: the reviewer must not be the same person who authored the timetable.
    if (this.authorId.equals(reviewerId)) {
      throw new InvalidStateTransitionException(
          ErrorCodes.TIMETABLE_SELF_APPROVAL_NOT_ALLOWED,
          String.format(
              "Timetable '%s': author '%s' cannot approve their own timetable.", id, reviewerId));
    }
    this.reviewerId = reviewerId;
    transitionTo(TimetableStatus.APPROVED, reviewerId);
    raiseDomainEvent(TimetableDomainEvent.approved(this, reviewerId));
  }

  /** Rejects the timetable with a mandatory reason. */
  public void reject(String reviewerId, String reason) {
    ApprovalStateMachine.assertTransitionAllowed(status, TimetableStatus.REJECTED, id.toString());
    if (this.authorId.equals(reviewerId)) {
      throw new InvalidStateTransitionException(
          ErrorCodes.TIMETABLE_SELF_APPROVAL_NOT_ALLOWED,
          "Author cannot reject their own timetable.");
    }
    if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Rejection reason must not be blank");
    this.reviewerId = reviewerId;
    transitionTo(TimetableStatus.REJECTED, reviewerId);
    raiseDomainEvent(TimetableDomainEvent.rejected(this, reviewerId, reason));
  }

  /** Request changes — returns APPROVED timetable back to DRAFT for rework. */
  public void requestChanges(String reviewerId) {
    ApprovalStateMachine.assertTransitionAllowed(status, TimetableStatus.DRAFT, id.toString());
    transitionTo(TimetableStatus.DRAFT, reviewerId);
    raiseDomainEvent(TimetableDomainEvent.changesRequested(this, reviewerId));
  }

  /** Activates the timetable on its effective date. Called by the scheduled activation job. */
  public void activate(String actor) {
    ApprovalStateMachine.assertTransitionAllowed(status, TimetableStatus.ACTIVE, id.toString());
    transitionTo(TimetableStatus.ACTIVE, actor);
    raiseDomainEvent(TimetableDomainEvent.activated(this, actor));
  }

  /**
   * Emergency activation — bypasses PENDING_REVIEW. Requires a mandatory justification string.
   * The command handler must verify the caller has EMERGENCY_OPERATOR role before calling this.
   *
   * @param actor Emergency operator user ID.
   * @param justification Mandatory explanation, recorded immutably in the audit log.
   * @throws InvalidStateTransitionException if justification is missing.
   */
  public void activateEmergency(String actor, String justification) {
    if (justification == null || justification.isBlank()) {
      throw new InvalidStateTransitionException(
          ErrorCodes.EMERGENCY_JUSTIFICATION_REQUIRED,
          String.format("Emergency activation of timetable '%s' requires a non-blank justification.", id));
    }
    // Emergency activation from DRAFT only — already-active timetables use activate().
    if (status != TimetableStatus.DRAFT && status != TimetableStatus.PENDING_REVIEW) {
      throw new InvalidStateTransitionException(
          ErrorCodes.TIMETABLE_INVALID_STATE_TRANSITION,
          String.format(
              "Emergency activation of timetable '%s' not allowed from status %s.", id, status));
    }
    transitionTo(TimetableStatus.EMERGENCY_ACTIVE, actor);
    raiseDomainEvent(TimetableDomainEvent.emergencyActivated(this, actor, justification));
  }

  /** Marks this timetable as superseded by a newer version for the same line. */
  public void supersede(String actor) {
    ApprovalStateMachine.assertTransitionAllowed(status, TimetableStatus.SUPERSEDED, id.toString());
    transitionTo(TimetableStatus.SUPERSEDED, actor);
    raiseDomainEvent(TimetableDomainEvent.superseded(this, actor));
  }

  /** Cancels the timetable. Only allowed before it becomes ACTIVE. */
  public void cancel(String actor) {
    ApprovalStateMachine.assertTransitionAllowed(status, TimetableStatus.CANCELLED, id.toString());
    transitionTo(TimetableStatus.CANCELLED, actor);
    raiseDomainEvent(TimetableDomainEvent.cancelled(this, actor));
  }

  // ── Domain event management ─────────────────────────────────────────────────

  /** Returns accumulated domain events (unmodifiable). */
  public List<TimetableDomainEvent> getDomainEvents() {
    return Collections.unmodifiableList(domainEvents);
  }

  /** Clears accumulated domain events after they have been persisted to the outbox. */
  public void clearDomainEvents() {
    domainEvents.clear();
  }

  // ── Internal helpers ────────────────────────────────────────────────────────

  private void transitionTo(TimetableStatus newStatus, String actor) {
    this.status = newStatus;
  }

  private void raiseDomainEvent(TimetableDomainEvent event) {
    domainEvents.add(event);
  }

  private void assertStatus(TimetableStatus expected, String operation) {
    if (this.status != expected) {
      throw new InvalidStateTransitionException(
          ErrorCodes.TIMETABLE_INVALID_STATE_TRANSITION,
          String.format(
              "Operation '%s' on timetable '%s' requires status %s, but current status is %s.",
              operation, id, expected, status));
    }
  }

  // ── Accessors ───────────────────────────────────────────────────────────────

  public TimetableId getId() { return id; }
  public LineId getLineId() { return lineId; }
  public TimetableStatus getStatus() { return status; }
  public String getName() { return name; }
  public String getDescription() { return description; }
  public LocalDate getEffectiveDate() { return effectiveDate; }
  public LocalDate getExpiryDate() { return expiryDate; }
  public String getAuthorId() { return authorId; }
  public String getReviewerId() { return reviewerId; }
  public long getVersion() { return version; }
}
