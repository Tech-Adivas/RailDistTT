package com.railway.platform.distribution.channel;

import com.railway.platform.events.DelayPredictionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Broadcasts delay prediction events to connected WebSocket/STOMP clients.
 *
 * <p>Destination strategy:
 * <ul>
 *   <li>{@code /topic/lines/{routeId}/predictions} — subscribers interested in a specific
 *       route's delay predictions</li>
 *   <li>{@code /topic/predictions} — all clients receiving the global prediction feed</li>
 * </ul>
 *
 * <p>WebSocket delivery is fire-and-forget. Clients that are offline at delivery time
 * will miss the push and should refetch via the query-service {@code GET /api/v1/predictions}
 * endpoint on reconnect.
 */
@Component
public class PredictionWebSocketDistributor {

  private static final Logger log = LoggerFactory.getLogger(PredictionWebSocketDistributor.class);

  private final SimpMessagingTemplate messagingTemplate;

  public PredictionWebSocketDistributor(SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = messagingTemplate;
  }

  /**
   * Pushes a delay prediction to WebSocket subscribers.
   *
   * @param event         the {@link DelayPredictionEvent} to broadcast
   * @param correlationId correlation ID inherited from the originating request chain
   */
  public void distribute(DelayPredictionEvent event, String correlationId) {
    String routeId = event.getRouteId();

    // Use a mutable map so we can put null for optional trainId
    Map<String, Object> payload = new HashMap<>();
    payload.put("routeId", routeId);
    payload.put("trainId", event.getTrainId());
    payload.put("predictedDelayMinutes", event.getPredictedDelayMinutes());
    payload.put("confidenceScore", event.getConfidenceScore());
    payload.put("modelVersion", event.getModelVersion());
    payload.put("predictedAt", Instant.now().toString());
    payload.put("correlationId", correlationId);

    // Broadcast to route-level topic
    messagingTemplate.convertAndSend("/topic/lines/" + routeId + "/predictions", payload);

    // Broadcast to global predictions topic
    messagingTemplate.convertAndSend("/topic/predictions", payload);

    log.info(
        "Prediction WebSocket push sent [routeId={}] [delayMin={}] [confidence={}]",
        routeId,
        event.getPredictedDelayMinutes(),
        event.getConfidenceScore());
  }
}
