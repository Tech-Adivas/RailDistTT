package com.railway.platform.timetable.api.dto;

import java.time.Instant;

/**
 * Read-only DTO representing a single entry in a timetable's audit trail.
 *
 * <p>Returned by the {@code GET /api/v1/timetables/{id}/audit} endpoint. Each entry captures
 * one state-changing event: who performed it, what state transition occurred, when it happened,
 * and (for emergency activations) why it was authorised out-of-process.
 */
public record AuditLogEntry(
    String id,
    String eventType,
    String previousStatus,
    String newStatus,
    String actor,
    String justification,
    String correlationId,
    Instant occurredAt
) {}
