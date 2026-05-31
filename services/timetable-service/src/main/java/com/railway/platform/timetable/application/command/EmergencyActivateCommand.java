package com.railway.platform.timetable.application.command;

/** Command to emergency-activate a timetable, bypassing the approval workflow. */
public record EmergencyActivateCommand(
    String timetableId,
    String actor,
    String justification   // mandatory; recorded immutably in the audit log
) {}
