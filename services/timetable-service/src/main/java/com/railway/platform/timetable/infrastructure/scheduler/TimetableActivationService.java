package com.railway.platform.timetable.infrastructure.scheduler;

import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.repository.TimetableRepository;
import com.railway.platform.timetable.infrastructure.persistence.outbox.AuditLogWriter;
import com.railway.platform.timetable.infrastructure.persistence.outbox.OutboxEventWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional helper for activating a single timetable.
 *
 * <p>Extracted from {@link TimetableActivationScheduler} as a separate Spring bean because
 * {@code @Transactional} on a private method in the same class is silently ignored by Spring AOP
 * (proxying only applies to public methods on distinct beans). Moving the transactional work here
 * ensures each activation runs in its own committed transaction.
 *
 * <p>Supersession and activation domain events are written to the Transactional Outbox and the
 * audit log in the same transaction as the aggregate state changes (ADR-001).
 */
@Service
public class TimetableActivationService {

  private static final Logger log = LoggerFactory.getLogger(TimetableActivationService.class);
  private static final String SYSTEM_ACTOR = "system:activation-scheduler";

  private final TimetableRepository repository;
  private final OutboxEventWriter outboxEventWriter;
  private final AuditLogWriter auditLogWriter;

  public TimetableActivationService(
      TimetableRepository repository,
      OutboxEventWriter outboxEventWriter,
      AuditLogWriter auditLogWriter) {
    this.repository = repository;
    this.outboxEventWriter = outboxEventWriter;
    this.auditLogWriter = auditLogWriter;
  }

  /**
   * Supersedes existing ACTIVE timetables for the same line, then activates the given timetable.
   * Runs in its own transaction — if this method throws, only this timetable's activation fails;
   * the scheduler loop continues with the next candidate.
   *
   * <p>All aggregate saves, outbox writes, and audit log entries are committed atomically.
   */
  @Transactional
  public void activateAndSupersede(Timetable timetableToActivate) {
    List<Timetable> currentlyActive = repository.findActiveByLineId(timetableToActivate.getLineId());
    for (Timetable active : currentlyActive) {
      active.supersede(SYSTEM_ACTOR);
      repository.save(active);
      outboxEventWriter.write(active.getDomainEvents());
      auditLogWriter.write(active.getDomainEvents(), SYSTEM_ACTOR);
      active.clearDomainEvents();
      log.info("Superseded timetable [id={}] for line [{}]", active.getId(), active.getLineId());
    }
    timetableToActivate.activate(SYSTEM_ACTOR);
    repository.save(timetableToActivate);
    outboxEventWriter.write(timetableToActivate.getDomainEvents());
    auditLogWriter.write(timetableToActivate.getDomainEvents(), SYSTEM_ACTOR);
    timetableToActivate.clearDomainEvents();
    log.info("Activated timetable [id={}] for line [{}] effective [{}]",
        timetableToActivate.getId(),
        timetableToActivate.getLineId(),
        timetableToActivate.getEffectiveDate());
  }
}
