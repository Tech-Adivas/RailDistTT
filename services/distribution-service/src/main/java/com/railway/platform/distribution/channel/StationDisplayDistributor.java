package com.railway.platform.distribution.channel;

import com.railway.platform.distribution.orchestrator.DistributionResult;
import com.railway.platform.events.DistributionChannel;
import com.railway.platform.events.ScheduleComputedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Distributes schedule updates to station display boards.
 *
 * <p>TODO: Phase 5 — integrate with the Station Display Management API.
 * Production implementation will POST to a per-station REST endpoint:
 *   POST ${PLACEHOLDER_STATION_DISPLAY_API_URL}/api/v1/lines/{lineId}/schedule
 * with the schedule payload. URL obtained from Vault:
 *   secret/distribution-service/station-display-api → base-url, api-key
 *
 * <p>Current stub logs the distribution intent and returns success so the rest of
 * the fan-out pipeline is exercised end-to-end.
 */
@Component
public class StationDisplayDistributor {

  private static final Logger log = LoggerFactory.getLogger(StationDisplayDistributor.class);

  public DistributionResult distribute(ScheduleComputedEvent event, boolean isEmergency) {
    log.info("Station display distribution [timetableId={}] [lineId={}] [isEmergency={}] — stub",
        event.getTimetableId(), event.getLineId(), isEmergency);

    return new DistributionResult(DistributionChannel.STATION_DISPLAY.name(), true, null);
  }
}
