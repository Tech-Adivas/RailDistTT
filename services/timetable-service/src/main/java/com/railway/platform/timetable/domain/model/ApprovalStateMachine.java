package com.railway.platform.timetable.domain.model;

import com.railway.platform.common.exception.InvalidStateTransitionException;
import com.railway.platform.common.error.ErrorCodes;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Enforces the valid state transitions for the Timetable aggregate.
 *
 * <p>This is the single authoritative place for transition logic. Every command handler invokes
 * {@link #assertTransitionAllowed} before mutating the aggregate status. Adding a new transition
 * requires changing this class only — not scattered conditionals across handlers.
 *
 * <p>The transition table encodes the state machine from docs/architecture.md exactly.
 * Any attempted transition not in the table throws {@link InvalidStateTransitionException} (422).
 */
public final class ApprovalStateMachine {

  private ApprovalStateMachine() {}

  /**
   * Maps each state to the set of states it is allowed to transition into.
   * EMERGENCY_ACTIVE is handled separately (requires role check by the command handler).
   */
  private static final Map<TimetableStatus, Set<TimetableStatus>> ALLOWED_TRANSITIONS =
      Map.of(
          TimetableStatus.DRAFT,
              EnumSet.of(TimetableStatus.PENDING_REVIEW, TimetableStatus.CANCELLED),
          TimetableStatus.PENDING_REVIEW,
              EnumSet.of(
                  TimetableStatus.DRAFT,
                  TimetableStatus.APPROVED,
                  TimetableStatus.REJECTED,
                  TimetableStatus.CANCELLED),
          TimetableStatus.APPROVED,
              EnumSet.of(TimetableStatus.ACTIVE, TimetableStatus.CANCELLED),
          TimetableStatus.ACTIVE,
              EnumSet.of(TimetableStatus.SUPERSEDED),
          TimetableStatus.EMERGENCY_ACTIVE,
              EnumSet.of(TimetableStatus.SUPERSEDED),
          // Terminal states — no outgoing transitions.
          TimetableStatus.REJECTED, EnumSet.noneOf(TimetableStatus.class),
          TimetableStatus.SUPERSEDED, EnumSet.noneOf(TimetableStatus.class),
          TimetableStatus.CANCELLED, EnumSet.noneOf(TimetableStatus.class));

  /**
   * Asserts that transitioning from {@code current} to {@code target} is permitted.
   *
   * @throws InvalidStateTransitionException if the transition is not allowed.
   */
  public static void assertTransitionAllowed(
      TimetableStatus current, TimetableStatus target, String timetableId) {

    Set<TimetableStatus> allowed = ALLOWED_TRANSITIONS.getOrDefault(current, Set.of());
    if (!allowed.contains(target)) {
      throw new InvalidStateTransitionException(
          ErrorCodes.TIMETABLE_INVALID_STATE_TRANSITION,
          String.format(
              "Timetable '%s' cannot transition from %s to %s. Allowed targets from %s: %s",
              timetableId, current, target, current, allowed));
    }
  }
}
