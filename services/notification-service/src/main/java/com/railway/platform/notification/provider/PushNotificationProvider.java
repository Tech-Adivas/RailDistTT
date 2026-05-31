package com.railway.platform.notification.provider;

import com.railway.platform.events.NotificationChannel;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stub FCM push provider. Logs delivery details; does not make real HTTP calls.
 *
 * <p>Swap in a real FCM client by replacing the body of {@link #send} — the interface contract
 * and DI wiring remain unchanged.
 */
@Component
public class PushNotificationProvider implements NotificationProvider {

  private static final Logger log = LoggerFactory.getLogger(PushNotificationProvider.class);

  // TODO(config): Vault path secret/notification-service/fcm → server-key
  @Value("${notification.fcm.server-key:PLACEHOLDER_FCM_SERVER_KEY}")
  private String serverKey;

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.PUSH;
  }

  @Override
  public void send(
      List<String> recipientIds,
      String title,
      String body,
      boolean isEmergency,
      String correlationId) {

    if (isEmergency) {
      log.warn(
          "EMERGENCY priority push sent [recipients={}] [title={}] [correlationId={}]",
          recipientIds.size(),
          title,
          correlationId);
      return;
    }

    log.info(
        "Push notification sent [recipients={}] [title={}] [correlationId={}]",
        recipientIds.size(),
        title,
        correlationId);
  }
}
