package com.railway.platform.timetable.api.dto.response;

import com.railway.platform.timetable.infrastructure.persistence.entity.AuditLogJpaEntity;

import java.time.Instant;

/** Response DTO for a single audit log entry. */
public record AuditLogEntryResponse(
    String id,
    String eventType,
    String previousStatus,
    String newStatus,
    String actor,
    String justification,
    Instant occurredAt
) {
  public static AuditLogEntryResponse from(AuditLogJpaEntity e) {
    return new AuditLogEntryResponse(
        e.getId().toString(),
        e.getEventType(),
        e.getPreviousStatus(),
        e.getNewStatus(),
        e.getActor(),
        e.getJustification(),
        e.getOccurredAt());
  }
}
