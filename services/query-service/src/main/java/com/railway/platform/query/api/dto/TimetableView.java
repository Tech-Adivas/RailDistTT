package com.railway.platform.query.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.railway.platform.query.infrastructure.persistence.entity.TimetableReadModelEntity;

import java.time.LocalDate;

/** Immutable read model DTO for a timetable. Serialised as JSON in Redis. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimetableView(
    String id,
    String lineId,
    String name,
    String description,
    String status,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String authorId,
    String reviewerId,
    Long version
) {
  public static TimetableView from(TimetableReadModelEntity e) {
    return new TimetableView(
        e.getId().toString(),
        e.getLineId(),
        e.getName(),
        e.getDescription(),
        e.getStatus(),
        e.getEffectiveDate(),
        e.getExpiryDate(),
        e.getAuthorId(),
        e.getReviewerId(),
        e.getVersion());
  }
}
