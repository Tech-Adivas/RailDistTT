package com.railway.platform.timetable.application.command;

import java.time.LocalDate;

/** Command to update mutable fields of a DRAFT timetable. */
public record UpdateTimetableCommand(
    String timetableId,
    String name,
    String description,
    LocalDate effectiveDate,
    LocalDate expiryDate,
    String actor
) {}
