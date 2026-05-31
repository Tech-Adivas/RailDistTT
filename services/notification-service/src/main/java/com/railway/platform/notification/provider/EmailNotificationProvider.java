package com.railway.platform.notification.provider;

import com.railway.platform.events.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stub SendGrid email provider.
 *
 * <p>TODO: Phase 5 — implement real SendGrid HTTP calls.
 * API key from Vault: secret/notification-service/sendgrid → api-key
 * From address from Vault: secret/notification-service/sendgrid → from-address
 */
@Component
public class EmailNotificationProvider implements NotificationProvider {

  private static final Logger log = LoggerFactory.getLogger(EmailNotificationProvider.class);

  // TODO(config): Obtain from Vault path secret/notification-service/sendgrid
  @Value("${notification.sendgrid.api-key:PLACEHOLDER_SENDGRID_API_KEY}")
  private String apiKey;

  @Value("${notification.sendgrid.from-address:PLACEHOLDER_SENDGRID_FROM_ADDRESS}")
  private String fromAddress;

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.EMAIL;
  }

  @Override
  public void send(List<String> recipientIds, String title, String body,
      boolean isEmergency, String correlationId) {
    for (String recipientId : recipientIds) {
      log.info("Email [to={}] [subject={}] [isEmergency={}] [correlationId={}] — stub",
          recipientId, title, isEmergency, correlationId);
    }
    if (recipientIds.isEmpty()) {
      log.info("Email to LINE_SUBSCRIBERS [subject={}] [isEmergency={}] — stub",
          title, isEmergency);
    }
  }
}
