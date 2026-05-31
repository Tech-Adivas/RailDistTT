package com.railway.platform.distribution.channel;

import com.railway.platform.distribution.orchestrator.DistributionResult;
import com.railway.platform.events.DistributionChannel;
import com.railway.platform.events.ScheduleComputedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Distributes schedule updates to registered partner feed endpoints.
 *
 * <p>TODO: Phase 5 — load partner feed URLs from a database table and POST to each.
 * Vault path for partner credentials: secret/distribution-service/partner-feeds/{partnerId}
 *
 * <p>Current stub logs intent and returns success.
 */
@Component
public class PartnerFeedDistributor {

  private static final Logger log = LoggerFactory.getLogger(PartnerFeedDistributor.class);

  public DistributionResult distribute(ScheduleComputedEvent event, boolean isEmergency) {
    log.info("Partner feed distribution [timetableId={}] [lineId={}] [isEmergency={}] — stub",
        event.getTimetableId(), event.getLineId(), isEmergency);

    return new DistributionResult(DistributionChannel.PARTNER_FEED.name(), true, null);
  }
}
