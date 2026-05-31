package com.railway.platform.timetable.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for POST /timetables/{id}/request-changes. */
public record RequestChangesRequest(
    @NotBlank(message = "feedback must not be blank")
    @Size(max = 2000)
    String feedback
) {}
