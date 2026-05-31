package com.railway.platform.schedule.integration;

import com.railway.platform.events.EventMetadata;
import com.railway.platform.events.TimetableChangeType;
import com.railway.platform.events.TimetableChangedEvent;
import com.railway.platform.events.Topics;
import com.railway.platform.schedule.infrastructure.persistence.repository.ComputedScheduleRepository;
import com.railway.platform.schedule.infrastructure.persistence.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test for the schedule-service consumer pipeline.
 *
 * <p>Verifies that a TimetableChangedEvent published to Kafka is:
 * <ol>
 *   <li>Consumed by TimetableChangedConsumer.</li>
 *   <li>Recorded in processed_events (idempotency).</li>
 *   <li>Persisted to computed_schedules.</li>
 * </ol>
 *
 * <p>Uses Testcontainers for real PostgreSQL and Kafka instances, so this test mirrors
 * production behaviour (including Flyway migration, JPA, and Kafka).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ScheduleServiceIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
      DockerImageName.parse("postgres:16-alpine"))
      .withDatabaseName("schedule_test_db")
      .withUsername("test")
      .withPassword("test");

  @Container
  static KafkaContainer kafka = new KafkaContainer(
      DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

  @DynamicPropertySource
  static void overrideProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    registry.add("spring.kafka.properties.schema.registry.url", () -> "mock://test");
  }

  @Autowired
  private KafkaTemplate<String, Object> kafkaTemplate;

  @Autowired
  private ProcessedEventRepository processedEventRepository;

  @Autowired
  private ComputedScheduleRepository computedScheduleRepository;

  @Test
  void whenTimetableChangedEventPublished_thenScheduleIsComputedAndPersisted() {
    String eventId = UUID.randomUUID().toString();
    String timetableId = UUID.randomUUID().toString();

    var event = buildEvent(eventId, timetableId);

    kafkaTemplate.executeInTransaction(
        ops -> ops.send(Topics.TIMETABLE_CHANGED, timetableId, event));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> {
          assertThat(processedEventRepository.existsByEventId(eventId)).isTrue();
          assertThat(computedScheduleRepository.findByTimetableId(timetableId)).isNotEmpty();
        });
  }

  @Test
  void whenDuplicateEventPublished_thenOnlyOneScheduleRecordIsCreated() {
    String eventId = UUID.randomUUID().toString();
    String timetableId = UUID.randomUUID().toString();

    var event = buildEvent(eventId, timetableId);

    // Publish the same event twice.
    kafkaTemplate.executeInTransaction(
        ops -> ops.send(Topics.TIMETABLE_CHANGED, timetableId, event));
    kafkaTemplate.executeInTransaction(
        ops -> ops.send(Topics.TIMETABLE_CHANGED, timetableId, event));

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> {
          assertThat(processedEventRepository.existsByEventId(eventId)).isTrue();
          // Idempotency: at most one computed schedule row per event.
          var schedules = computedScheduleRepository.findByTimetableId(timetableId);
          assertThat(schedules).hasSizeLessThanOrEqualTo(1);
        });
  }

  private TimetableChangedEvent buildEvent(String eventId, String timetableId) {
    var metadata = EventMetadata.newBuilder()
        .setEventId(eventId)
        .setEventType("TimetableChangedEvent")
        .setOccurredAt(Instant.now().toEpochMilli())
        .setCorrelationId("it-test-correlation-" + eventId)
        .setActor("integration-test")
        .setSchemaVersion(1)
        .build();

    return TimetableChangedEvent.newBuilder()
        .setMetadata(metadata)
        .setTimetableId(timetableId)
        .setLineId("GWR-PAD-BRI")
        .setChangeType(TimetableChangeType.APPROVED)
        .setVersion(1L)
        .setPreviousStatus("PENDING_REVIEW")
        .setNewStatus("APPROVED")
        .setEffectiveDate(LocalDate.of(2026, 6, 1))
        .setExpiryDate(LocalDate.of(2026, 12, 31))
        .build();
  }
}
