package com.railway.platform.timetable.domain.valueobject;

/**
 * Strongly-typed identifier for a railway line.
 *
 * <p>Line IDs are assigned by the infrastructure team and referenced by timetables.
 * They are not UUIDs — they follow an operator-defined naming convention (e.g. "GWR-PAD-BRI").
 */
public record LineId(String value) {

  public LineId {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException("LineId must not be blank");
  }

  public static LineId of(String value) {
    return new LineId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
