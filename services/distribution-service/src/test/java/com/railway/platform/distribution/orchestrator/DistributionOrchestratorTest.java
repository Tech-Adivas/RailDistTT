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
import com.railway.platform.events.EventMetadata;
import com.railway.platform.events.ScheduleComputedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistributionOrchestratorTest {

  @Mock private WebSocketDistributor webSocketDistributor;
  @Mock private PassengerAppDistributor passengerAppDistributor;
  @Mock private StationDisplayDistributor stationDisplayDistributor;
  @Mock private PartnerFeedDistributor partnerFeedDistributor;
  @Mock private DistributionEventProducer distributionEventProducer;
  @Mock private DistributionTrackingRepository trackingRepository;

  @InjectMocks
  private DistributionOrchestrator orchestrator;

  @Test
  void whenScheduleComputed_thenAllFourChannelsDistributed() {
    var event = buildEvent("system:schedule-service");
    String correlationId = "test-correlation";

    when(webSocketDistributor.distribute(any(), anyBoolean()))
        .thenReturn(new DistributionResult(DistributionChannel.WEBSOCKET_PUSH.name(), true, null));
    when(passengerAppDistributor.distribute(any(), anyBoolean(), any()))
        .thenReturn(new DistributionResult(DistributionChannel.PASSENGER_APP.name(), true, null));
    when(stationDisplayDistributor.distribute(any(), anyBoolean()))
        .thenReturn(new DistributionResult(DistributionChannel.STATION_DISPLAY.name(), true, null));
    when(partnerFeedDistributor.distribute(any(), anyBoolean()))
        .thenReturn(new DistributionResult(DistributionChannel.PARTNER_FEED.name(), true, null));

    orchestrator.distribute(event, correlationId);

    verify(webSocketDistributor).distribute(event, false);
    verify(passengerAppDistributor).distribute(event, false, correlationId);
    verify(stationDisplayDistributor).distribute(event, false);
    verify(partnerFeedDistributor).distribute(event, false);

    // 4 channels → 4 DistributionEvent publishes
    verify(distributionEventProducer, times(4))
        .publish(any(), any(), any(), eq(DistributionStatus.PUBLISHED), isNull(), eq(false), any());
  }

  @Test
  void whenAllNonWsChannelsFail_thenCompensationEventEmitted() {
    var event = buildEvent("system:schedule-service");

    when(webSocketDistributor.distribute(any(), anyBoolean()))
        .thenReturn(new DistributionResult(DistributionChannel.WEBSOCKET_PUSH.name(), true, null));
    when(passengerAppDistributor.distribute(any(), anyBoolean(), any()))
        .thenReturn(new DistributionResult(DistributionChannel.PASSENGER_APP.name(), false, "timeout"));
    when(stationDisplayDistributor.distribute(any(), anyBoolean()))
        .thenReturn(new DistributionResult(DistributionChannel.STATION_DISPLAY.name(), false, "unreachable"));
    when(partnerFeedDistributor.distribute(any(), anyBoolean()))
        .thenReturn(new DistributionResult(DistributionChannel.PARTNER_FEED.name(), false, "error"));

    orchestrator.distribute(event, "corr-123");

    // Compensation event must be published when all non-WS channels fail
    verify(distributionEventProducer).publish(
        any(), any(), any(), eq(DistributionStatus.COMPENSATED), any(), anyBoolean(), any());
  }

  @Test
  void whenEmergencyEvent_thenEmergencyBroadcastChannelIncluded() {
    var event = buildEvent("system:emergency-activation-scheduler");

    when(webSocketDistributor.distribute(any(), eq(true)))
        .thenReturn(new DistributionResult(DistributionChannel.WEBSOCKET_PUSH.name(), true, null));
    when(passengerAppDistributor.distribute(any(), eq(true), any()))
        .thenReturn(new DistributionResult(DistributionChannel.PASSENGER_APP.name(), true, null));
    when(stationDisplayDistributor.distribute(any(), eq(true)))
        .thenReturn(new DistributionResult(DistributionChannel.STATION_DISPLAY.name(), true, null));
    when(partnerFeedDistributor.distribute(any(), eq(true)))
        .thenReturn(new DistributionResult(DistributionChannel.PARTNER_FEED.name(), true, null));

    orchestrator.distribute(event, "corr-emergency");

    // 5 channels for emergency (includes EMERGENCY_BROADCAST)
    verify(distributionEventProducer, times(5))
        .publish(any(), any(), any(), eq(DistributionStatus.PUBLISHED), isNull(), eq(true), any());

    var trackingCaptor = ArgumentCaptor.forClass(DistributionTrackingEntity.class);
    verify(trackingRepository, times(5)).save(trackingCaptor.capture());
    assertThat(trackingCaptor.getAllValues())
        .anyMatch(e -> e.getChannel().equals(DistributionChannel.EMERGENCY_BROADCAST.name()));
  }

  private ScheduleComputedEvent buildEvent(String actor) {
    var metadata = EventMetadata.newBuilder()
        .setEventId(UUID.randomUUID().toString())
        .setEventType("ScheduleComputedEvent")
        .setOccurredAt(Instant.now().toEpochMilli())
        .setCorrelationId("test-correlation")
        .setActor(actor)
        .setSchemaVersion(1)
        .build();

    return ScheduleComputedEvent.newBuilder()
        .setMetadata(metadata)
        .setTimetableId(UUID.randomUUID().toString())
        .setLineId("GWR-PAD-BRI")
        .setEffectiveDate(LocalDate.of(2026, 6, 1))
        .setExpiryDate(LocalDate.of(2026, 12, 31))
        .setServices(List.of())
        .setTriggeringEventId(UUID.randomUUID().toString())
        .build();
  }
}
