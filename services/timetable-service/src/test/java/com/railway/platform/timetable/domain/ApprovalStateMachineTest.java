package com.railway.platform.timetable.domain;

import com.railway.platform.common.exception.InvalidStateTransitionException;
import com.railway.platform.timetable.domain.model.ApprovalStateMachine;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

@Tag("unit")
class ApprovalStateMachineTest {

  @ParameterizedTest
  @MethodSource("validTransitions")
  void assertTransitionAllowed_validTransition_doesNotThrow(TransitionCase tc) {
    assertThatCode(() ->
        ApprovalStateMachine.assertTransitionAllowed(tc.from(), tc.to(), "test-id"))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @MethodSource("invalidTransitions")
  void assertTransitionAllowed_invalidTransition_throwsException(TransitionCase tc) {
    assertThatThrownBy(() ->
        ApprovalStateMachine.assertTransitionAllowed(tc.from(), tc.to(), "test-id"))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  void terminalState_cancelled_cannotTransitionToAnything() {
    for (TimetableStatus target : TimetableStatus.values()) {
      if (target != TimetableStatus.CANCELLED) {
        assertThatThrownBy(() ->
            ApprovalStateMachine.assertTransitionAllowed(TimetableStatus.CANCELLED, target, "x"))
            .isInstanceOf(InvalidStateTransitionException.class);
      }
    }
  }

  static Stream<TransitionCase> validTransitions() {
    return Stream.of(
        new TransitionCase(TimetableStatus.DRAFT, TimetableStatus.PENDING_REVIEW),
        new TransitionCase(TimetableStatus.DRAFT, TimetableStatus.CANCELLED),
        new TransitionCase(TimetableStatus.PENDING_REVIEW, TimetableStatus.APPROVED),
        new TransitionCase(TimetableStatus.PENDING_REVIEW, TimetableStatus.REJECTED),
        new TransitionCase(TimetableStatus.PENDING_REVIEW, TimetableStatus.DRAFT),
        new TransitionCase(TimetableStatus.PENDING_REVIEW, TimetableStatus.CANCELLED),
        new TransitionCase(TimetableStatus.APPROVED, TimetableStatus.ACTIVE),
        new TransitionCase(TimetableStatus.APPROVED, TimetableStatus.CANCELLED),
        new TransitionCase(TimetableStatus.ACTIVE, TimetableStatus.SUPERSEDED),
        new TransitionCase(TimetableStatus.EMERGENCY_ACTIVE, TimetableStatus.SUPERSEDED)
    );
  }

  static Stream<TransitionCase> invalidTransitions() {
    return Stream.of(
        new TransitionCase(TimetableStatus.DRAFT, TimetableStatus.APPROVED),
        new TransitionCase(TimetableStatus.DRAFT, TimetableStatus.ACTIVE),
        new TransitionCase(TimetableStatus.REJECTED, TimetableStatus.DRAFT),
        new TransitionCase(TimetableStatus.REJECTED, TimetableStatus.PENDING_REVIEW),
        new TransitionCase(TimetableStatus.SUPERSEDED, TimetableStatus.ACTIVE),
        new TransitionCase(TimetableStatus.CANCELLED, TimetableStatus.DRAFT),
        new TransitionCase(TimetableStatus.ACTIVE, TimetableStatus.APPROVED)
    );
  }

  record TransitionCase(TimetableStatus from, TimetableStatus to) {}
}
