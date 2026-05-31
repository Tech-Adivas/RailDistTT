package com.railway.platform.timetable.application.command;

/** Command to cancel a timetable before activation. */
public record CancelCommand(String timetableId, String actor) {}
