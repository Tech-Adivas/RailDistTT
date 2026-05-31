package com.railway.platform.query.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.railway.platform.query.infrastructure.persistence.entity.ScheduleReadModelEntity;

import java.time.Instant;
import java.time.LocalDate;

/** Immutable read model DTO for a computed schedule. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleView(
    String id,
    String timetableId,
    String lineId,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String scheduleData,
    Instant computedAt
) {
  public static ScheduleView from(ScheduleReadModelEntity e) {
    return new ScheduleView(
        e.getId().toString(),
        e.getTimetableId(),
        e.getLineId(),
        e.getEffectiveDate(),
        e.getExpiryDate(),
        e.getScheduleData(),
        e.getComputedAt());
  }
}
