package com.railway.platform.query.application;

import com.railway.platform.common.error.ErrorCodes;
import com.railway.platform.query.api.dto.PagedResponse;
import com.railway.platform.query.api.dto.TimetableView;
import com.railway.platform.query.infrastructure.cache.TimetableCacheService;
import com.railway.platform.query.infrastructure.persistence.repository.TimetableReadModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Application service for timetable read queries.
 *
 * <p>Cache-aside pattern:
 * <ol>
 *   <li>Check Redis — return if hit.</li>
 *   <li>Query PostgreSQL read model — throw 404 if absent.</li>
 *   <li>Populate Redis cache.</li>
 *   <li>Return result.</li>
 * </ol>
 */
@Service
@Transactional(readOnly = true)
public class TimetableQueryService {

  private static final Logger log = LoggerFactory.getLogger(TimetableQueryService.class);

  private final TimetableReadModelRepository repository;
  private final TimetableCacheService cache;

  public TimetableQueryService(
      TimetableReadModelRepository repository,
      TimetableCacheService cache) {
    this.repository = repository;
    this.cache = cache;
  }

  /**
   * Returns a single timetable by ID with cache-aside.
   *
   * @throws NoSuchElementException if no timetable exists for the given ID.
   */
  public TimetableView getById(String timetableId) {
    var cached = cache.get(timetableId);
    if (cached.isPresent()) {
      log.debug("Cache hit [timetableId={}]", timetableId);
      return cached.get();
    }

    log.debug("Cache miss [timetableId={}] — querying DB", timetableId);

    var entity = repository.findById(UUID.fromString(timetableId))
        .orElseThrow(() -> new NoSuchElementException(
            ErrorCodes.TIMETABLE_NOT_FOUND + ": " + timetableId));

    var view = TimetableView.from(entity);
    cache.put(view);
    return view;
  }

  /**
   * Lists all timetables for a railway line, optionally filtered by status.
   */
  public List<TimetableView> listByLine(String lineId, String status) {
    var entities = (status != null && !status.isBlank())
        ? repository.findByLineIdAndStatusOrderByEffectiveDateDesc(lineId, status)
        : repository.findByLineIdOrderByEffectiveDateDesc(lineId);

    return entities.stream().map(TimetableView::from).toList();
  }

  public PagedResponse<TimetableView> listByLine(String lineId, String status, int page, int size) {
    var all = listByLine(lineId, status);
    long total = all.size();
    int fromIndex = Math.min(page * size, all.size());
    int toIndex = Math.min(fromIndex + size, all.size());
    var pageContent = all.subList(fromIndex, toIndex);
    return PagedResponse.of(pageContent, page, size, total);
  }
}
