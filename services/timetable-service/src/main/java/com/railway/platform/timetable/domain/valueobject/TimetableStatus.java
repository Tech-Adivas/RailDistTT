package com.railway.platform.timetable.domain.valueobject;

/**
 * All valid lifecycle states for a Timetable aggregate.
 *
 * <p>Transitions are enforced by the ApprovalStateMachine. See docs/architecture.md for the state
 * diagram. Direct mutation of status without going through the state machine is a domain invariant
 * violation.
 */
public enum TimetableStatus {
  /**
   * Initial state. Author is still editing. No events emitted.
   * Transitions: → PENDING_REVIEW (submit), → CANCELLED
   */
  DRAFT,

  /**
   * Submitted for approval. Awaiting a reviewer (who must differ from the author).
   * Transitions: → DRAFT (request changes), → APPROVED, → REJECTED, → CANCELLED
   */
  PENDING_REVIEW,

  /**
   * Approved by a reviewer. Awaiting activation on effective date.
   * Transitions: → ACTIVE (activation job), → CANCELLED (before activation)
   */
  APPROVED,

  /**
   * Rejected by a reviewer. Cannot be resubmitted; author must create a new draft.
   * Terminal state.
   */
  REJECTED,

  /**
   * Active and currently served to passengers and displays.
   * Transitions: → SUPERSEDED (when a newer timetable activates for the same line)
   */
  ACTIVE,

  /**
   * Emergency activation — bypasses PENDING_REVIEW. Requires actor to have EMERGENCY_OPERATOR
   * role and a mandatory justification string. Post-hoc review required within 24 hours.
   * Transitions: → SUPERSEDED
   */
  EMERGENCY_ACTIVE,

  /**
   * Replaced by a newer version for the same line and date range.
   * Terminal state.
   */
  SUPERSEDED,

  /**
   * Cancelled before activation. Terminal state.
   */
  CANCELLED
}
