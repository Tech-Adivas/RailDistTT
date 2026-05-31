package com.railway.platform.timetable.application.command;

/** Command to request changes — returns a PENDING_REVIEW timetable back to DRAFT for rework. */
public record RequestChangesCommand(String timetableId, String reviewerId, String feedback) {}
