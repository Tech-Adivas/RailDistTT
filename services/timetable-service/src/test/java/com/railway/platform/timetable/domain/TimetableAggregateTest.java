package com.railway.platform.timetable.domain;

import com.railway.platform.common.exception.InvalidStateTransitionException;
import com.railway.platform.timetable.domain.event.TimetableDomainEvent;
import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.valueobject.LineId;
import com.railway.platform.timetable.domain.valueobject.TimetableId;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the Timetable aggregate domain model.
 * These run without Spring context — pure domain logic only.
 */
@Tag("unit")
class TimetableAggregateTest {

  private static final String AUTHOR = "author-user-1";
  private static final String REVIEWER = "reviewer-user-2";
  private static final LocalDate FUTURE_DATE = LocalDate.now().plusDays(10);
  private static final LocalDate LATER_DATE  = LocalDate.now().plusDays(20);

  private Timetable timetable;

  @BeforeEach
  void setUp() {
    timetable = Timetable.create(
        TimetableId.generate(),
        LineId.of("GWR-PAD-BRI"),
        "Paddington–Bristol Timetable 2027",
        "Summer 2027 service",
        FUTURE_DATE,
        LATER_DATE,
        AUTHOR);
  }

  // ── Creation ────────────────────────────────────────────────────────────────

  @Nested
  class Creation {
    @Test
    void create_startsInDraftStatus() {
      assertThat(timetable.getStatus()).isEqualTo(TimetableStatus.DRAFT);
    }

    @Test
    void create_raisesCreatedEvent() {
      assertThat(timetable.getDomainEvents()).hasSize(1);
      assertThat(timetable.getDomainEvents().get(0).eventType())
          .isEqualTo(TimetableDomainEvent.EventType.CREATED);
    }

    @Test
    void create_withNullId_throwsIllegalArgument() {
      assertThatThrownBy(() ->
          Timetable.create(null, LineId.of("X"), "n", null, FUTURE_DATE, null, AUTHOR))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_withExpiryBeforeEffective_throwsIllegalArgument() {
      assertThatThrownBy(() ->
          Timetable.create(TimetableId.generate(), LineId.of("X"), "n", null,
              FUTURE_DATE, FUTURE_DATE.minusDays(1), AUTHOR))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("expiryDate must be after effectiveDate");
    }
  }

  // ── Approval workflow ────────────────────────────────────────────────────────

  @Nested
  class ApprovalWorkflow {
    @Test
    void submitForReview_transitionsToPendingReview() {
      timetable.submitForReview(AUTHOR);
      assertThat(timetable.getStatus()).isEqualTo(TimetableStatus.PENDING_REVIEW);
    }

    @Test
    void approve_byDifferentUser_transitionsToApproved() {
      timetable.submitForReview(AUTHOR);
      timetable.approve(REVIEWER);
      assertThat(timetable.getStatus()).isEqualTo(TimetableStatus.APPROVED);
    }

    @Test
    void approve_bySameAuthor_throwsInvalidStateTransition() {
      timetable.submitForReview(AUTHOR);
      assertThatThrownBy(() -> timetable.approve(AUTHOR))
          .isInstanceOf(InvalidStateTransitionException.class)
          .hasMessageContaining("self");
    }

    @Test
    void approve_fromDraftDirectly_throwsInvalidStateTransition() {
      // Must submit for review first.
      assertThatThrownBy(() -> timetable.approve(REVIEWER))
          .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void reject_withReason_transitionsToRejected() {
      timetable.submitForReview(AUTHOR);
      timetable.reject(REVIEWER, "Schedule conflicts with maintenance window");
      assertThat(timetable.getStatus()).isEqualTo(TimetableStatus.REJECTED);
    }

    @Test
    void reject_withBlankReason_throwsIllegalArgument() {
      timetable.submitForReview(AUTHOR);
      assertThatThrownBy(() -> timetable.reject(REVIEWER, "  "))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullApprovalFlow_raisesCorrectEvents() {
      timetable.clearDomainEvents();
      timetable.submitForReview(AUTHOR);
      timetable.approve(REVIEWER);
      timetable.activate("system");

      var types = timetable.getDomainEvents().stream()
          .map(TimetableDomainEvent::eventType).toList();
      assertThat(types).containsExactly(
          TimetableDomainEvent.EventType.SUBMITTED_FOR_REVIEW,
          TimetableDomainEvent.EventType.APPROVED,
          TimetableDomainEvent.EventType.ACTIVATED);
    }
  }

  // ── Emergency activation ─────────────────────────────────────────────────────

  @Nested
  class EmergencyActivation {
    @Test
    void emergencyActivate_fromDraft_withJustification_transitionsToEmergencyActive() {
      timetable.activateEmergency("emergency-op", "Train service disruption due to track failure");
      assertThat(timetable.getStatus()).isEqualTo(TimetableStatus.EMERGENCY_ACTIVE);
    }

    @Test
    void emergencyActivate_withoutJustification_throwsInvalidStateTransition() {
      assertThatThrownBy(() -> timetable.activateEmergency("emergency-op", ""))
          .isInstanceOf(InvalidStateTransitionException.class)
          .hasMessageContaining("justification");
    }

    @Test
    void emergencyActivate_raisesEmergencyActivatedEvent() {
      timetable.clearDomainEvents();
      timetable.activateEmergency("emergency-op", "Flood on line");
      var event = timetable.getDomainEvents().get(0);
      assertThat(event.eventType()).isEqualTo(TimetableDomainEvent.EventType.EMERGENCY_ACTIVATED);
      assertThat(event.justification()).isEqualTo("Flood on line");
    }
  }

  // ── Terminal states ──────────────────────────────────────────────────────────

  @Nested
  class TerminalStates {
    @Test
    void cancel_fromDraft_transitionsToCancelled() {
      timetable.cancel(AUTHOR);
      assertThat(timetable.getStatus()).isEqualTo(TimetableStatus.CANCELLED);
    }

    @Test
    void cancel_fromCancelled_throwsInvalidStateTransition() {
      timetable.cancel(AUTHOR);
      assertThatThrownBy(() -> timetable.cancel(AUTHOR))
          .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void supersede_fromActive_transitionsToSuperseded() {
      timetable.submitForReview(AUTHOR);
      timetable.approve(REVIEWER);
      timetable.activate("system");
      timetable.supersede("system");
      assertThat(timetable.getStatus()).isEqualTo(TimetableStatus.SUPERSEDED);
    }
  }

  // ── Domain event lifecycle ───────────────────────────────────────────────────

  @Nested
  class DomainEventLifecycle {
    @Test
    void clearDomainEvents_removesAllEvents() {
      assertThat(timetable.getDomainEvents()).isNotEmpty();
      timetable.clearDomainEvents();
      assertThat(timetable.getDomainEvents()).isEmpty();
    }

    @Test
    void getDomainEvents_returnsUnmodifiableList() {
      assertThatThrownBy(() -> timetable.getDomainEvents().add(null))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }
}
