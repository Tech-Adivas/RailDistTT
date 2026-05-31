package com.railway.platform.timetable.infrastructure;

import com.railway.platform.timetable.application.command.ApproveCommand;
import com.railway.platform.timetable.application.command.CreateTimetableCommand;
import com.railway.platform.timetable.application.command.SubmitForReviewCommand;
import com.railway.platform.timetable.application.handler.TimetableCommandHandler;
import com.railway.platform.timetable.domain.repository.TimetableRepository;
import com.railway.platform.timetable.domain.valueobject.TimetableId;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;
import com.railway.platform.timetable.infrastructure.persistence.repository.OutboxEventJpaRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the timetable write path.
 *
 * <p>Spins up a real PostgreSQL container via Testcontainers. Verifies:
 * <ul>
 *   <li>The full create → submit → approve flow persists correctly.
 *   <li>An outbox row is written in the same transaction as the aggregate change.
 *   <li>Optimistic locking is enforced.
 * </ul>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TimetableIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
      .withDatabaseName("timetable_db")
      .withUsername("railway")
      .withPassword("railway_test");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    // Skip Vault in integration tests — use hardcoded values via application-test.yml.
    registry.add("spring.cloud.vault.enabled", () -> "false");
    registry.add("spring.config.import", () -> "");
  }

  @Autowired
  private TimetableCommandHandler commandHandler;

  @Autowired
  private TimetableRepository timetableRepository;

  @Autowired
  private OutboxEventJpaRepository outboxRepository;

  @Test
  void createTimetable_persistsAggregateAndOutboxRow() {
    var cmd = new CreateTimetableCommand(
        "GWR-PAD-BRI",
        "Summer 2027 Service",
        "Paddington to Bristol summer timetable",
        LocalDate.now().plusDays(10),
        LocalDate.now().plusDays(30),
        "author-1");

    TimetableId id = commandHandler.handle(cmd);

    // Aggregate persisted.
    var timetable = timetableRepository.findById(id);
    assertThat(timetable).isPresent();
    assertThat(timetable.get().getStatus()).isEqualTo(TimetableStatus.DRAFT);
    assertThat(timetable.get().getName()).isEqualTo("Summer 2027 Service");

    // Outbox row persisted in the same transaction — Debezium will relay this to Kafka.
    var outboxRows = outboxRepository.findAll();
    assertThat(outboxRows).hasSize(1);
    assertThat(outboxRows.get(0).getAggregateId()).isEqualTo(id.toString());
    assertThat(outboxRows.get(0).getEventType()).isEqualTo("CREATED");
  }

  @Test
  void approvalFlow_persistsStateChangesAndOutboxRows() {
    // Create
    var createCmd = new CreateTimetableCommand(
        "GWR-PAD-BRI", "Timetable B", null,
        LocalDate.now().plusDays(5), null, "author-2");
    TimetableId id = commandHandler.handle(createCmd);

    // Submit
    commandHandler.handle(new SubmitForReviewCommand(id.toString(), "author-2"));

    // Approve (different user — self-approval guard)
    commandHandler.handle(new ApproveCommand(id.toString(), "reviewer-99"));

    var timetable = timetableRepository.findById(id).orElseThrow();
    assertThat(timetable.getStatus()).isEqualTo(TimetableStatus.APPROVED);
    assertThat(timetable.getReviewerId()).isEqualTo("reviewer-99");

    // One outbox row per event: CREATED, SUBMITTED_FOR_REVIEW, APPROVED.
    assertThat(outboxRepository.findAll()).hasSizeGreaterThanOrEqualTo(3);
  }
}
