package com.railway.platform.timetable.infrastructure.scheduler;

import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.repository.TimetableRepository;
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
 */
@Service
public class TimetableActivationService {

  private static final Logger log = LoggerFactory.getLogger(TimetableActivationService.class);
  private static final String SYSTEM_ACTOR = "system:activation-scheduler";

  private final TimetableRepository repository;

  public TimetableActivationService(TimetableRepository repository) {
    this.repository = repository;
  }

  /**
   * Supersedes existing ACTIVE timetables for the same line, then activates the given timetable.
   * Runs in its own transaction — if this method throws, only this timetable's activation fails;
   * the scheduler loop continues with the next candidate.
   */
  @Transactional
  public void activateAndSupersede(Timetable timetableToActivate) {
    List<Timetable> currentlyActive = repository.findActiveByLineId(timetableToActivate.getLineId());
    for (Timetable active : currentlyActive) {
      active.supersede(SYSTEM_ACTOR);
      repository.save(active);
      log.info("Superseded timetable [id={}] for line [{}]", active.getId(), active.getLineId());
    }
    timetableToActivate.activate(SYSTEM_ACTOR);
    repository.save(timetableToActivate);
    log.info("Activated timetable [id={}] for line [{}] effective [{}]",
        timetableToActivate.getId(),
        timetableToActivate.getLineId(),
        timetableToActivate.getEffectiveDate());
  }
}
