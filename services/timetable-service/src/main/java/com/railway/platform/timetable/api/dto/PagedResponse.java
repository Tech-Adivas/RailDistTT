package com.railway.platform.timetable.api.dto;

import java.util.List;

/**
 * Generic paginated response wrapper.
 *
 * <p>Used by list endpoints that support page-based pagination. Callers should use the
 * {@link #of} factory method rather than the canonical constructor.
 */
public record PagedResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean last
) {
  public static <T> PagedResponse<T> of(List<T> content, int page, int size, long totalElements) {
    int totalPages = size == 0 ? 1 : (int) Math.ceil((double) totalElements / size);
    return new PagedResponse<>(content, page, size, totalElements, totalPages, page >= totalPages - 1);
  }
}
