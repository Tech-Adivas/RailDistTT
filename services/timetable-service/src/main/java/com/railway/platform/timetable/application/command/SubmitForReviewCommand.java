package com.railway.platform.timetable.application.command;

/** Command to submit a DRAFT timetable for reviewer approval. */
public record SubmitForReviewCommand(String timetableId, String actor) {}
