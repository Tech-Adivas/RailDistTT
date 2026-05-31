package com.railway.platform.distribution.channel;

import com.railway.platform.distribution.orchestrator.DistributionResult;
import com.railway.platform.events.DistributionChannel;
import com.railway.platform.events.ScheduleComputedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Distributes schedule updates to connected WebSocket/STOMP clients.
 *
 * <p>Destination strategy:
 * <ul>
 *   <li>{@code /topic/lines/{lineId}/schedule} — all subscribers to a line's schedule updates</li>
 *   <li>{@code /topic/emergency} — all connected clients, for emergency broadcasts only</li>
 * </ul>
 *
 * <p>WebSocket delivery is fire-and-forget from the perspective of this service. Clients that
 * are offline at delivery time will miss the push and must refetch via the query-service API on
 * reconnect. This is acceptable because the query-service read model is the source of truth.
 */
@Component
public class WebSocketDistributor {

  private static final Logger log = LoggerFactory.getLogger(WebSocketDistributor.class);

  private final SimpMessagingTemplate messagingTemplate;

  public WebSocketDistributor(SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = messagingTemplate;
  }

  public DistributionResult distribute(ScheduleComputedEvent event, boolean isEmergency) {
    String lineId = event.getLineId().toString();
    String timetableId = event.getTimetableId().toString();

    var payload = Map.of(
        "timetableId", timetableId,
        "lineId", lineId,
        "effectiveDate", event.getEffectiveDate().toString(),
        "triggeringEventId", event.getTriggeringEventId().toString(),
        "isEmergency", isEmergency);

    messagingTemplate.convertAndSend("/topic/lines/" + lineId + "/schedule", payload);

    if (isEmergency) {
      messagingTemplate.convertAndSend("/topic/emergency", payload);
      log.warn("EMERGENCY WebSocket broadcast sent [timetableId={}] [lineId={}]",
          timetableId, lineId);
    }

    log.info("WebSocket push sent [timetableId={}] [lineId={}] [isEmergency={}]",
        timetableId, lineId, isEmergency);

    return new DistributionResult(DistributionChannel.WEBSOCKET_PUSH.name(), true, null);
  }
}
