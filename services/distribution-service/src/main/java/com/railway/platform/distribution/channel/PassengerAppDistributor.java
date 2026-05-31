package com.railway.platform.distribution.channel;

import com.railway.platform.distribution.orchestrator.DistributionResult;
import com.railway.platform.distribution.producer.NotificationRequestProducer;
import com.railway.platform.events.DistributionChannel;
import com.railway.platform.events.ScheduleComputedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Distributes schedule updates to passenger app subscribers via NotificationRequestEvent.
 *
 * <p>The actual push delivery (FCM, etc.) is handled by the notification-service; this
 * component is responsible only for publishing the request event.
 */
@Component
public class PassengerAppDistributor {

  private static final Logger log = LoggerFactory.getLogger(PassengerAppDistributor.class);

  private final NotificationRequestProducer notificationProducer;

  public PassengerAppDistributor(NotificationRequestProducer notificationProducer) {
    this.notificationProducer = notificationProducer;
  }

  public DistributionResult distribute(ScheduleComputedEvent event, boolean isEmergency,
      String correlationId) {
    try {
      notificationProducer.publish(
          event.getTimetableId().toString(),
          event.getLineId().toString(),
          event.getEffectiveDate().toString(),
          isEmergency,
          correlationId);

      log.info("Passenger app notification published [timetableId={}] [isEmergency={}]",
          event.getTimetableId(), isEmergency);

      return new DistributionResult(DistributionChannel.PASSENGER_APP.name(), true, null);

    } catch (Exception e) {
      log.error("Failed to publish passenger app notification [timetableId={}]: {}",
          event.getTimetableId(), e.getMessage());
      return new DistributionResult(DistributionChannel.PASSENGER_APP.name(), false, e.getMessage());
    }
  }
}
