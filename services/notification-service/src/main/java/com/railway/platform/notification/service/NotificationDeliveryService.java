package com.railway.platform.notification.service;

import com.railway.platform.events.NotificationChannel;
import com.railway.platform.events.NotificationRequestEvent;
import com.railway.platform.notification.infrastructure.persistence.entity.SentNotificationEntity;
import com.railway.platform.notification.infrastructure.persistence.repository.SentNotificationRepository;
import com.railway.platform.notification.provider.NotificationProvider;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatches a NotificationRequestEvent to all requested delivery channels.
 *
 * <p>Template rendering is applied once before iterating channels. Each provider
 * call is independent — a failure in one channel does not prevent delivery via others,
 * but both failures are logged.
 */
@Service
public class NotificationDeliveryService {

  private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryService.class);

  private final Map<NotificationChannel, NotificationProvider> providers;
  private final SentNotificationRepository sentNotificationRepository;
  private final TemplateRenderer templateRenderer;
  private final Counter deliveryCounter;
  private final Counter errorCounter;

  public NotificationDeliveryService(
      Map<NotificationChannel, NotificationProvider> providers,
      SentNotificationRepository sentNotificationRepository,
      TemplateRenderer templateRenderer,
      MeterRegistry meterRegistry) {
    this.providers = providers;
    this.sentNotificationRepository = sentNotificationRepository;
    this.templateRenderer = templateRenderer;
    this.deliveryCounter = meterRegistry.counter("notification.deliveries.total");
    this.errorCounter = meterRegistry.counter("notification.delivery.errors.total");
  }

  @Timed(value = "notification.delivery.duration", description = "Time to deliver notification across all channels")
  public void deliver(NotificationRequestEvent event, String correlationId) {
    String eventId = event.getMetadata().getEventId().toString();
    boolean isEmergency = event.getIsEmergency();

    @SuppressWarnings("unchecked")
    Map<String, String> variables = (Map<String, String>) (Map<?, ?>) event.getTemplateVariables();
    String renderedTitle = templateRenderer.render(event.getTitleTemplate().toString(), variables);
    String renderedBody  = templateRenderer.render(event.getBodyTemplate().toString(), variables);

    if (isEmergency) {
      log.warn("EMERGENCY notification delivery [eventId={}] [type={}]",
          eventId, event.getNotificationType());
    }

    List<String> recipientIds = event.getRecipientIds().stream()
        .map(Object::toString)
        .toList();

    for (Object channelObj : event.getChannels()) {
      NotificationChannel channel = (NotificationChannel) channelObj;
      NotificationProvider provider = providers.get(channel);

      if (provider == null) {
        log.warn("No provider configured for channel [{}] — skipping", channel);
        continue;
      }

      try {
        provider.send(recipientIds, renderedTitle, renderedBody, isEmergency, correlationId);
        deliveryCounter.increment();
        recordDelivery(eventId, recipientIds, channel, event, renderedTitle, renderedBody,
            isEmergency, correlationId);
      } catch (Exception e) {
        errorCounter.increment();
        log.error("Delivery failed [channel={}] [eventId={}]: {}", channel, eventId, e.getMessage());
        // Continue delivering to other channels — partial delivery is better than none.
      }
    }
  }

  private void recordDelivery(String eventId, List<String> recipientIds,
      NotificationChannel channel, NotificationRequestEvent event,
      String title, String body, boolean isEmergency, String correlationId) {

    // Record one row per recipient, or one row for bulk (empty recipientIds list)
    List<String> targets = recipientIds.isEmpty() ? List.of("BULK") : recipientIds;

    for (String recipientId : targets) {
      var entity = new SentNotificationEntity();
      entity.setId(UUID.randomUUID());
      entity.setEventId(eventId);
      entity.setRecipientId(recipientId);
      entity.setChannel(channel.name());
      entity.setNotificationType(event.getNotificationType().name());
      entity.setTitle(title);
      entity.setBody(body);
      entity.setEmergency(isEmergency);
      entity.setSentAt(Instant.now());
      entity.setCorrelationId(correlationId);
      sentNotificationRepository.save(entity);
    }
  }
}
