package com.railway.platform.notification.config;

import com.railway.platform.events.NotificationChannel;
import com.railway.platform.notification.provider.NotificationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
public class NotificationProviderConfig {

  /**
   * Builds a channel → provider lookup map from all registered NotificationProvider beans.
   * Adding a new channel requires only a new @Component implementing NotificationProvider.
   */
  @Bean
  public Map<NotificationChannel, NotificationProvider> providerMap(
      List<NotificationProvider> providers) {
    return providers.stream()
        .collect(Collectors.toMap(NotificationProvider::channel, p -> p));
  }
}
