package com.railway.platform.distribution.orchestrator;

import com.railway.platform.distribution.channel.PartnerFeedDistributor;
import com.railway.platform.distribution.channel.PassengerAppDistributor;
import com.railway.platform.distribution.channel.StationDisplayDistributor;
import com.railway.platform.distribution.channel.WebSocketDistributor;
import com.railway.platform.distribution.infrastructure.persistence.entity.DistributionTrackingEntity;
import com.railway.platform.distribution.infrastructure.persistence.repository.DistributionTrackingRepository;
import com.railway.platform.distribution.producer.DistributionEventProducer;
import com.railway.platform.events.DistributionChannel;
import com.railway.platform.events.DistributionStatus;
import com.railway.platform.events.ScheduleComputedEvent;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Coordinates fan-out of a ScheduleComputedEvent to all distribution channels.
 *
 * <p>Fan-out order:
 * <ol>
 *   <li>WEBSOCKET_PUSH — fire-and-forget, always attempted first for lowest latency</li>
 *   <li>PASSENGER_APP — via NotificationRequestEvent to Kafka</li>
 *   <li>STATION_DISPLAY</li>
 *   <li>PARTNER_FEED</li>
 * </ol>
 *
 * <p>For emergency events, EMERGENCY_BROADCAST is also published (same payload as WEBSOCKET_PUSH
 * but with a dedicated channel name for downstream consumers to distinguish priority).
 *
 * <p>Saga compensation: if all non-WebSocket channels fail, a COMPENSATED DistributionEvent is
 * emitted to signal downstream consumers to discard any partial updates.
 */
@Component
public class DistributionOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(DistributionOrchestrator.class);

  private final WebSocketDistributor webSocketDistributor;
  private final PassengerAppDistributor passengerAppDistributor;
  private final StationDisplayDistributor stationDisplayDistributor;
  private final PartnerFeedDistributor partnerFeedDistributor;
  private final DistributionEventProducer distributionEventProducer;
  private final DistributionTrackingRepository trackingRepository;
  private final Counter failureCounter;

  public DistributionOrchestrator(
      WebSocketDistributor webSocketDistributor,
      PassengerAppDistributor passengerAppDistributor,
      StationDisplayDistributor stationDisplayDistributor,
      PartnerFeedDistributor partnerFeedDistributor,
      DistributionEventProducer distributionEventProducer,
      DistributionTrackingRepository trackingRepository,
      MeterRegistry meterRegistry) {
    this.webSocketDistributor = webSocketDistributor;
    this.passengerAppDistributor = passengerAppDistributor;
    this.stationDisplayDistributor = stationDisplayDistributor;
    this.partnerFeedDistributor = partnerFeedDistributor;
    this.distributionEventProducer = distributionEventProducer;
    this.trackingRepository = trackingRepository;
    this.failureCounter = meterRegistry.counter("distribution.channel.failures.total");
  }

  /**
   * Distributes the schedule update to all channels and records per-channel results.
   * Called within a Kafka transaction so all DistributionEvent publishes and DB writes are atomic.
   */
  @Timed(value = "distribution.orchestration.duration", description = "Time to fan-out schedule update to all channels")
  public void distribute(ScheduleComputedEvent event, String correlationId) {
    String timetableId = event.getTimetableId().toString();
    String scheduleEventId = event.getMetadata().getEventId().toString();
    boolean isEmergency = isEmergencyEvent(event);

    List<DistributionResult> results = new ArrayList<>();

    // WebSocket — always first, lowest latency
    results.add(webSocketDistributor.distribute(event, isEmergency));

    // Async channel fan-out
    results.add(passengerAppDistributor.distribute(event, isEmergency, correlationId));
    results.add(stationDisplayDistributor.distribute(event, isEmergency));
    results.add(partnerFeedDistributor.distribute(event, isEmergency));

    // Emergency broadcast channel (same payload, separate tracking record)
    if (isEmergency) {
      results.add(new DistributionResult(
          DistributionChannel.EMERGENCY_BROADCAST.name(), true, null));
      log.warn("EMERGENCY distribution complete [timetableId={}]", timetableId);
    }

    // Publish DistributionEvent + persist tracking for each channel
    for (var result : results) {
      var channel = DistributionChannel.valueOf(result.channel());
      var status = result.success() ? DistributionStatus.PUBLISHED : DistributionStatus.FAILED;

      if (!result.success()) {
        failureCounter.increment();
      }

      distributionEventProducer.publish(
          scheduleEventId, timetableId, channel, status,
          result.failureReason(), isEmergency, correlationId);

      persist(timetableId, scheduleEventId, result, isEmergency);
    }

    // Saga compensation: if every non-WebSocket channel failed, signal compensation
    long failures = results.stream()
        .filter(r -> !r.channel().equals(DistributionChannel.WEBSOCKET_PUSH.name()))
        .filter(r -> !r.success())
        .count();
    long nonWsChannels = results.stream()
        .filter(r -> !r.channel().equals(DistributionChannel.WEBSOCKET_PUSH.name()))
        .count();

    if (nonWsChannels > 0 && failures == nonWsChannels) {
      log.error("All non-WebSocket channels failed for [timetableId={}] — emitting COMPENSATED",
          timetableId);
      distributionEventProducer.publish(
          scheduleEventId, timetableId,
          DistributionChannel.PASSENGER_APP, DistributionStatus.COMPENSATED,
          "All channels failed", isEmergency, correlationId);
    }
  }

  /**
   * Derives emergency status from the schedule event metadata.
   * An event is emergency if the actor indicates an emergency activation.
   * TODO: Phase 5 — carry isEmergency flag explicitly in ScheduleComputedEvent schema.
   */
  private boolean isEmergencyEvent(ScheduleComputedEvent event) {
    String actor = event.getMetadata().getActor().toString().toLowerCase();
    return actor.contains("emergency");
  }

  private void persist(String timetableId, String scheduleEventId,
      DistributionResult result, boolean isEmergency) {
    var entity = new DistributionTrackingEntity();
    entity.setId(UUID.randomUUID());
    entity.setTimetableId(timetableId);
    entity.setScheduleEventId(scheduleEventId);
    entity.setChannel(result.channel());
    entity.setStatus(result.success() ? "PUBLISHED" : "FAILED");
    entity.setFailureReason(result.failureReason());
    entity.setEmergency(isEmergency);
    entity.setDistributedAt(Instant.now());
    trackingRepository.save(entity);
  }
}
