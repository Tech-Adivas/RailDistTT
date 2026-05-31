package com.railway.platform.timetable.application.command;

/** Command to reject a timetable with a mandatory reason. */
public record RejectCommand(String timetableId, String reviewerId, String reason) {}
