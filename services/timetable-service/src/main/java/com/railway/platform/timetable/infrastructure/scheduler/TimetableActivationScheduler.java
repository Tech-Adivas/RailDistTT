package com.railway.platform.timetable.infrastructure.scheduler;

import com.railway.platform.timetable.application.command.CancelCommand;
import com.railway.platform.timetable.application.handler.TimetableCommandHandler;
import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.repository.TimetableRepository;
import com.railway.platform.timetable.domain.valueobject.LineId;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Scheduled job that activates APPROVED timetables when their effective date arrives
 * and supersedes previously ACTIVE timetables for the same line.
 *
 * <p>Runs once per minute. In production this could be triggered by an event rather than
 * polling — left as a scheduled job for simplicity in Phase 1.
 *
 * <p>Each activation is a separate transaction. If one timetable fails to activate
 * (e.g. due to an optimistic lock), the others are unaffected.
 */
@Component
public class TimetableActivationScheduler {

  private static final Logger log = LoggerFactory.getLogger(TimetableActivationScheduler.class);
  private static final String SYSTEM_ACTOR = "system:activation-scheduler";

  private final TimetableRepository repository;
  private final TimetableCommandHandler commandHandler;

  public TimetableActivationScheduler(
      TimetableRepository repository, TimetableCommandHandler commandHandler) {
    this.repository = repository;
    this.commandHandler = commandHandler;
  }

  /**
   * Activates all APPROVED timetables whose effective date has arrived.
   * For each activation, supersedes the previously ACTIVE timetable for the same line.
   */
  @Scheduled(fixedDelay = 60_000) // every 60 seconds
  public void activateDueTimetables() {
    LocalDate today = LocalDate.now();
    List<Timetable> approved = repository.findByStatus(TimetableStatus.APPROVED);

    for (Timetable timetable : approved) {
      if (!timetable.getEffectiveDate().isAfter(today)) {
        try {
          activateAndSupersede(timetable);
        } catch (Exception ex) {
          // One failure must not block the others.
          log.error("Failed to activate timetable [id={}]: {}", timetable.getId(), ex.getMessage(), ex);
        }
      }
    }
  }

  @Transactional
  private void activateAndSupersede(Timetable timetableToActivate) {
    // Supersede any currently active timetable for this line first.
    List<Timetable> currentlyActive = repository.findActiveByLineId(timetableToActivate.getLineId());
    for (Timetable active : currentlyActive) {
      active.supersede(SYSTEM_ACTOR);
      repository.save(active);
      log.info("Superseded timetable [id={}] for line [{}]",
          active.getId(), active.getLineId());
    }

    // Activate the new timetable.
    timetableToActivate.activate(SYSTEM_ACTOR);
    repository.save(timetableToActivate);
    log.info("Activated timetable [id={}] for line [{}] effective [{}]",
        timetableToActivate.getId(),
        timetableToActivate.getLineId(),
        timetableToActivate.getEffectiveDate());
  }
}
