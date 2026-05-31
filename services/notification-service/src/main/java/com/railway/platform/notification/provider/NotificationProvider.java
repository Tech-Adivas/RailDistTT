package com.railway.platform.notification.provider;

import com.railway.platform.events.NotificationChannel;
import java.util.List;

/** Strategy interface for a single notification channel. */
public interface NotificationProvider {

  NotificationChannel channel();

  void send(
      List<String> recipientIds,
      String title,
      String body,
      boolean isEmergency,
      String correlationId);
}
