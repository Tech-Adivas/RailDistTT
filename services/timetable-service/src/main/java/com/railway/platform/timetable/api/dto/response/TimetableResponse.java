package com.railway.platform.timetable.api.dto.response;

import com.railway.platform.timetable.domain.model.Timetable;
import com.railway.platform.timetable.domain.valueobject.TimetableStatus;

import java.time.LocalDate;

/** Response body for timetable read operations. */
public record TimetableResponse(
    String id,
    String lineId,
    String name,
    String description,
    TimetableStatus status,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String authorId,
    String reviewerId,
    long version
) {
  public static TimetableResponse from(Timetable t) {
    return new TimetableResponse(
        t.getId().toString(),
        t.getLineId().toString(),
        t.getName(),
        t.getDescription(),
        t.getStatus(),
        t.getEffectiveDate(),
        t.getExpiryDate(),
        t.getAuthorId(),
        t.getReviewerId(),
        t.getVersion());
  }
}
