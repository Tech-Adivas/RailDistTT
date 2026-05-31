package com.railway.platform.timetable.application.handler;

import com.railway.platform.common.exception.NotFoundException;
import com.railway.platform.common.error.ErrorCodes;
import com.railway.platform.timetable.application.command.*;
import com.railway.platform.timetable.domain.event.TimetableDomainEvent;
import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.repository.TimetableRepository;
import com.railway.platform.timetable.domain.valueobject.LineId;
import com.railway.platform.timetable.domain.valueobject.TimetableId;
import com.railway.platform.timetable.infrastructure.persistence.outbox.OutboxEventWriter;
import com.railway.platform.timetable.infrastructure.persistence.outbox.AuditLogWriter;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Application-layer orchestrator for all timetable write commands.
 *
 * <p>Each method is a single, atomic database transaction. Within that transaction:
 * <ol>
 *   <li>The aggregate is loaded and its invariants are enforced by domain methods.
 *   <li>The updated aggregate is persisted (optimistic lock version is checked by JPA).
 *   <li>The domain events are written to the outbox table.
 *   <li>The audit log entry is written.
 *   <li>The transaction commits — all of the above succeed or all roll back.
 * </ol>
 *
 * <p>Debezium CDC then reads the committed outbox rows and publishes events to Kafka asynchronously
 * (typically < 500ms after commit). No Kafka call is made inside this transaction.
 *
 * <p>Optimistic lock conflicts ({@link OptimisticLockingFailureException}) are caught and
 * rethrown as {@link com.railway.platform.common.exception.OptimisticLockException} (409)
 * so the REST layer can return the correct HTTP status.
 */
@Service
public class TimetableCommandHandler {

  private static final Logger log = LoggerFactory.getLogger(TimetableCommandHandler.class);

  private final TimetableRepository repository;
  private final OutboxEventWriter outboxEventWriter;
  private final AuditLogWriter auditLogWriter;
  private final Counter approvalCounter;
  private final Counter emergencyActivationCounter;

  public TimetableCommandHandler(
      TimetableRepository repository,
      OutboxEventWriter outboxEventWriter,
      AuditLogWriter auditLogWriter,
      MeterRegistry meterRegistry) {
    this.repository = repository;
    this.outboxEventWriter = outboxEventWriter;
    this.auditLogWriter = auditLogWriter;
    // Named metrics visible in Grafana. See infra/observability/ for dashboard panels.
    this.approvalCounter = meterRegistry.counter("timetable.approvals.total");
    this.emergencyActivationCounter = meterRegistry.counter("timetable.emergency.activations.total");
  }

  // ── Create ──────────────────────────────────────────────────────────────────

  @Timed(value = "timetable.command.duration", extraTags = {"command", "create"})
  @Retryable(retryFor = {org.springframework.dao.TransientDataAccessException.class, org.springframework.dao.CannotAcquireLockException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 1000))
  @Transactional
  public TimetableId handle(CreateTimetableCommand cmd) {
    var timetable = Timetable.create(
        TimetableId.generate(),
        LineId.of(cmd.lineId()),
        cmd.name(),
        cmd.description(),
        cmd.effectiveDate(),
        cmd.expiryDate(),
        cmd.authorId());

    persistWithOutboxAndAudit(timetable, cmd.authorId());
    log.info("Timetable created [id={}] [line={}] [actor={}]",
        timetable.getId(), cmd.lineId(), cmd.authorId());
    return timetable.getId();
  }

  // ── Update ──────────────────────────────────────────────────────────────────

  @Timed(value = "timetable.command.duration", extraTags = {"command", "update"})
  @Retryable(retryFor = {org.springframework.dao.TransientDataAccessException.class, org.springframework.dao.CannotAcquireLockException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 1000))
  @Transactional
  public void handle(UpdateTimetableCommand cmd) {
    var timetable = load(cmd.timetableId());
    timetable.update(cmd.name(), cmd.description(), cmd.effectiveDate(), cmd.expiryDate(), cmd.actor());
    persistWithOutboxAndAudit(timetable, cmd.actor());
    log.info("Timetable updated [id={}] [actor={}]", cmd.timetableId(), cmd.actor());
  }

  // ── Submit for review ───────────────────────────────────────────────────────

  @Timed(value = "timetable.command.duration", extraTags = {"command", "submit"})
  @Retryable(retryFor = {org.springframework.dao.TransientDataAccessException.class, org.springframework.dao.CannotAcquireLockException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 1000))
  @Transactional
  public void handle(SubmitForReviewCommand cmd) {
    var timetable = load(cmd.timetableId());
    timetable.submitForReview(cmd.actor());
    persistWithOutboxAndAudit(timetable, cmd.actor());
    log.info("Timetable submitted for review [id={}] [actor={}]", cmd.timetableId(), cmd.actor());
  }

  // ── Approve ─────────────────────────────────────────────────────────────────

  @Timed(value = "timetable.command.duration", extraTags = {"command", "approve"})
  @Retryable(retryFor = {org.springframework.dao.TransientDataAccessException.class, org.springframework.dao.CannotAcquireLockException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 1000))
  @Transactional
  public void handle(ApproveCommand cmd) {
    var timetable = load(cmd.timetableId());
    timetable.approve(cmd.reviewerId());
    persistWithOutboxAndAudit(timetable, cmd.reviewerId());
    approvalCounter.increment();
    log.info("Timetable approved [id={}] [reviewer={}]", cmd.timetableId(), cmd.reviewerId());
  }

  // ── Reject ──────────────────────────────────────────────────────────────────

  @Timed(value = "timetable.command.duration", extraTags = {"command", "reject"})
  @Retryable(retryFor = {org.springframework.dao.TransientDataAccessException.class, org.springframework.dao.CannotAcquireLockException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 1000))
  @Transactional
  public void handle(RejectCommand cmd) {
    var timetable = load(cmd.timetableId());
    timetable.reject(cmd.reviewerId(), cmd.reason());
    persistWithOutboxAndAudit(timetable, cmd.reviewerId());
    log.info("Timetable rejected [id={}] [reviewer={}]", cmd.timetableId(), cmd.reviewerId());
  }

  // ── Emergency activate ──────────────────────────────────────────────────────

  @Timed(value = "timetable.command.duration", extraTags = {"command", "emergency-activate"})
  @Retryable(retryFor = {org.springframework.dao.TransientDataAccessException.class, org.springframework.dao.CannotAcquireLockException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 1000))
  @Transactional
  public void handle(EmergencyActivateCommand cmd) {
    var timetable = load(cmd.timetableId());
    timetable.activateEmergency(cmd.actor(), cmd.justification());
    persistWithOutboxAndAudit(timetable, cmd.actor());
    emergencyActivationCounter.increment();
    // Log at WARN — emergency activations are significant operational events.
    log.warn("EMERGENCY ACTIVATION [id={}] [actor={}] [justification={}]",
        cmd.timetableId(), cmd.actor(), cmd.justification());
  }

  // ── Request changes ─────────────────────────────────────────────────────────

  @Timed(value = "timetable.command.duration", extraTags = {"command", "request-changes"})
  @Retryable(retryFor = {org.springframework.dao.TransientDataAccessException.class, org.springframework.dao.CannotAcquireLockException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 1000))
  @Transactional
  public void handle(RequestChangesCommand cmd) {
    var timetable = load(cmd.timetableId());
    timetable.requestChanges(cmd.reviewerId());
    persistWithOutboxAndAudit(timetable, cmd.reviewerId());
    log.info("Changes requested [id={}] [reviewer={}]", cmd.timetableId(), cmd.reviewerId());
  }

  // ── Cancel ──────────────────────────────────────────────────────────────────

  @Timed(value = "timetable.command.duration", extraTags = {"command", "cancel"})
  @Retryable(retryFor = {org.springframework.dao.TransientDataAccessException.class, org.springframework.dao.CannotAcquireLockException.class}, maxAttempts = 3, backoff = @Backoff(delay = 100, multiplier = 2.0, maxDelay = 1000))
  @Transactional
  public void handle(CancelCommand cmd) {
    var timetable = load(cmd.timetableId());
    timetable.cancel(cmd.actor());
    persistWithOutboxAndAudit(timetable, cmd.actor());
    log.info("Timetable cancelled [id={}] [actor={}]", cmd.timetableId(), cmd.actor());
  }

  // ── Internal helpers ────────────────────────────────────────────────────────

  /**
   * Persist aggregate, write outbox events, write audit entries — all in the caller's transaction.
   *
   * <p>This is the linchpin of the Transactional Outbox pattern (ADR-001). The three writes below
   * must be in the same transaction. If the DB commit fails, none of them persist, and Debezium
   * never sees an outbox row — so no phantom event is published to Kafka.
   */
  private void persistWithOutboxAndAudit(Timetable timetable, String actor) {
    List<TimetableDomainEvent> events = timetable.getDomainEvents();

    try {
      // 1. Persist aggregate state. JPA @Version check fires here — throws on conflict.
      repository.save(timetable);

      // 2. Write outbox rows for each domain event. Debezium reads these after commit.
      outboxEventWriter.write(events);

      // 3. Append to the immutable audit log.
      auditLogWriter.write(events, actor);

    } catch (OptimisticLockingFailureException ex) {
      // Translate JPA optimistic lock exception to the domain-layer type (409 response).
      throw new com.railway.platform.common.exception.OptimisticLockException(
          "Timetable", timetable.getId().toString());
    }

    // Only clear domain events after successful persist — if any step above threw, the
    // events remain on the aggregate so the caller's catch block can inspect them.
    timetable.clearDomainEvents();
  }

  private Timetable load(String id) {
    return repository
        .findById(TimetableId.of(id))
        .orElseThrow(
            () -> new NotFoundException(ErrorCodes.TIMETABLE_NOT_FOUND,
                "Timetable not found: " + id));
  }
}
