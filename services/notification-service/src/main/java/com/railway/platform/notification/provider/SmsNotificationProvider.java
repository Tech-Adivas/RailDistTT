package com.railway.platform.notification.provider;

import com.railway.platform.events.NotificationChannel;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stub Twilio SMS provider. Logs delivery details; does not make real HTTP calls.
 *
 * <p>Swap in the Twilio Java SDK by replacing the body of {@link #send}.
 */
@Component
public class SmsNotificationProvider implements NotificationProvider {

  private static final Logger log = LoggerFactory.getLogger(SmsNotificationProvider.class);

  // TODO(config): Vault path secret/notification-service/twilio → account-sid
  @Value("${notification.twilio.account-sid:PLACEHOLDER_TWILIO_ACCOUNT_SID}")
  private String accountSid;

  // TODO(config): Vault path secret/notification-service/twilio → auth-token
  @Value("${notification.twilio.auth-token:PLACEHOLDER_TWILIO_AUTH_TOKEN}")
  private String authToken;

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.SMS;
  }

  @Override
  public void send(
      List<String> recipientIds,
      String title,
      String body,
      boolean isEmergency,
      String correlationId) {

    log.info(
        "SMS notification sent [recipients={}] [title={}] [emergency={}] [correlationId={}]",
        recipientIds.size(),
        title,
        isEmergency,
        correlationId);
  }
}
