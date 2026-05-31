package com.railway.platform.timetable.domain.valueobject;

import java.util.UUID;

/**
 * Strongly-typed identifier for the Timetable aggregate.
 *
 * <p>Using a wrapper type rather than a raw UUID prevents accidental mixing of different entity IDs
 * at the parameter level — the compiler enforces the distinction.
 */
public record TimetableId(UUID value) {

  public TimetableId {
    if (value == null) throw new IllegalArgumentException("TimetableId value must not be null");
  }

  public static TimetableId generate() {
    return new TimetableId(UUID.randomUUID());
  }

  public static TimetableId of(String id) {
    return new TimetableId(UUID.fromString(id));
  }

  public static TimetableId of(UUID id) {
    return new TimetableId(id);
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
