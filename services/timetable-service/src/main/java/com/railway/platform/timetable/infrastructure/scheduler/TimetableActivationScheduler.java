package com.railway.platform.timetable.infrastructure.scheduler;

import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.repository.TimetableRepository;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Scheduled job that activates APPROVED timetables when their effective date arrives.
 *
 * <p>Runs every 60 seconds. Delegates each activation to {@link TimetableActivationService},
 * which runs in its own {@code @Transactional} bean — required because Spring AOP cannot proxy
 * private methods in the same class. One failure never blocks the rest of the batch.
 */
@Component
public class TimetableActivationScheduler {

  private static final Logger log = LoggerFactory.getLogger(TimetableActivationScheduler.class);

  private final TimetableRepository repository;
  private final TimetableActivationService activationService;

  public TimetableActivationScheduler(
      TimetableRepository repository,
      TimetableActivationService activationService) {
    this.repository = repository;
    this.activationService = activationService;
  }

  @Scheduled(fixedDelay = 60_000)
  public void activateDueTimetables() {
    LocalDate today = LocalDate.now();
    List<Timetable> approved = repository.findByStatus(TimetableStatus.APPROVED);

    for (Timetable timetable : approved) {
      if (!timetable.getEffectiveDate().isAfter(today)) {
        try {
          activationService.activateAndSupersede(timetable);
        } catch (Exception ex) {
          log.error("Failed to activate timetable [id={}]: {}", timetable.getId(), ex.getMessage(), ex);
        }
      }
    }
  }
}
