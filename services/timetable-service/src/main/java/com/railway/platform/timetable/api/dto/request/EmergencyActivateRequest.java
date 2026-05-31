package com.railway.platform.timetable.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for POST /timetables/{id}/emergency-activate. Requires EMERGENCY_OPERATOR role. */
public record EmergencyActivateRequest(
    @NotBlank(message = "justification must not be blank — emergency activations require a recorded reason")
    @Size(max = 2000, message = "justification must not exceed 2000 characters")
    String justification
) {}
