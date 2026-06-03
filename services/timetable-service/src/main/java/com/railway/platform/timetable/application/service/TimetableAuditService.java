package com.railway.platform.timetable.application.service;

import com.railway.platform.timetable.api.dto.AuditLogEntry;
import com.railway.platform.timetable.api.dto.PagedResponse;
import com.railway.platform.timetable.infrastructure.persistence.repository.AuditLogJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only application service for querying the audit trail of a timetable aggregate.
 *
 * <p>Intentionally separated from {@code TimetableCommandHandler} to keep the read path
 * lightweight (no domain aggregate loading, no outbox involvement) and independently testable.
 *
 * <p>Page size is capped at 100 to protect the database from unbounded result sets.
 */
@Service
public class TimetableAuditService {

  private static final int MAX_PAGE_SIZE = 100;

  private final AuditLogJpaRepository auditLogRepository;

  public TimetableAuditService(AuditLogJpaRepository auditLogRepository) {
    this.auditLogRepository = auditLogRepository;
  }

  /**
   * Returns a page of audit log entries for the given timetable, ordered most-recent first.
   *
   * @param timetableId The timetable aggregate ID to query.
   * @param page        Zero-based page index.
   * @param size        Entries per page; silently capped at {@value #MAX_PAGE_SIZE}.
   * @return A {@link PagedResponse} containing the requested slice and pagination metadata.
   */
  @Transactional(readOnly = true)
  public PagedResponse<AuditLogEntry> getAuditHistory(String timetableId, int page, int size) {
    if (size > MAX_PAGE_SIZE) {
      size = MAX_PAGE_SIZE;
    }
    var pageable = PageRequest.of(page, size, Sort.by("occurredAt").descending());
    var pagedEntities = auditLogRepository.findByAggregateIdOrderByOccurredAtDesc(timetableId, pageable);
    var content = pagedEntities.getContent().stream()
        .map(e -> new AuditLogEntry(
            e.getId().toString(),
            e.getEventType(),
            e.getPreviousStatus(),
            e.getNewStatus(),
            e.getActor(),
            e.getJustification(),
            e.getCorrelationId(),
            e.getOccurredAt()))
        .toList();
    return PagedResponse.of(content, page, size, pagedEntities.getTotalElements());
  }
}
