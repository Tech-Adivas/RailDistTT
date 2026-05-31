package com.railway.platform.timetable.application.command;

/** Command to approve a timetable in PENDING_REVIEW state. */
public record ApproveCommand(String timetableId, String reviewerId) {}
