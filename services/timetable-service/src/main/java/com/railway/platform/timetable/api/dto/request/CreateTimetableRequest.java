package com.railway.platform.timetable.api.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Request body for POST /timetables. */
public record CreateTimetableRequest(
    @NotBlank(message = "lineId must not be blank")
    String lineId,

    @NotBlank(message = "name must not be blank")
    @Size(max = 200, message = "name must not exceed 200 characters")
    String name,

    @Size(max = 2000, message = "description must not exceed 2000 characters")
    String description,

    @NotNull(message = "effectiveDate must not be null")
    @FutureOrPresent(message = "effectiveDate must be today or in the future")
    LocalDate effectiveDate,

    // Nullable — open-ended timetables have no expiry.
    LocalDate expiryDate
) {}
