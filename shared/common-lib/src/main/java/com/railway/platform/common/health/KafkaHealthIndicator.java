package com.railway.platform.common.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Health indicator that verifies the Kafka broker is reachable by listing topics
 * with a short timeout. Returns DOWN if the admin client times out, so Kubernetes
 * liveness probes can detect a broker partition.
 */
@Component
public class KafkaHealthIndicator implements HealthIndicator {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Override
    public Health health() {
        try (AdminClient adminClient = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                       AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000",
                       AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "3000"))) {
            adminClient.listTopics().names().get(3, TimeUnit.SECONDS);
            return Health.up().withDetail("bootstrapServers", bootstrapServers).build();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("bootstrapServers", bootstrapServers)
                    .withException(ex)
                    .build();
        }
    }
}
