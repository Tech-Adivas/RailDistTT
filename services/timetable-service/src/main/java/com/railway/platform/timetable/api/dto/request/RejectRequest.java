package com.railway.platform.timetable.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for POST /timetables/{id}/reject. */
public record RejectRequest(
    @NotBlank(message = "reason must not be blank")
    @Size(max = 1000, message = "reason must not exceed 1000 characters")
    String reason
) {}
