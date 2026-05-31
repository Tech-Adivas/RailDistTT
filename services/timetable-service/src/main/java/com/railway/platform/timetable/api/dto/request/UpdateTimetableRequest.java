package com.railway.platform.timetable.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Request body for PATCH /timetables/{id}. Only DRAFT timetables may be updated. */
public record UpdateTimetableRequest(
    @NotBlank(message = "name must not be blank")
    @Size(max = 200)
    String name,

    @Size(max = 2000)
    String description,

    @NotNull(message = "effectiveDate must not be null")
    LocalDate effectiveDate,

    LocalDate expiryDate
) {}
