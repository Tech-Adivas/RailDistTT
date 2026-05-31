package com.railway.platform.timetable.application.command;

import java.time.LocalDate;

/**
 * Command to create a new timetable in DRAFT state.
 * Validated at the REST layer before reaching the handler.
 */
public record CreateTimetableCommand(
    String lineId,
    String name,
    String description,
    LocalDate effectiveDate,
    LocalDate expiryDate,  // nullable
    String authorId        // extracted from the JWT principal by the REST controller
) {}
