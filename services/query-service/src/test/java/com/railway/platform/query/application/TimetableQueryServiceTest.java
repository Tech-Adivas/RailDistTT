package com.railway.platform.query.application;

import com.railway.platform.query.api.dto.TimetableView;
import com.railway.platform.query.infrastructure.cache.TimetableCacheService;
import com.railway.platform.query.infrastructure.persistence.entity.TimetableReadModelEntity;
import com.railway.platform.query.infrastructure.persistence.repository.TimetableReadModelRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TimetableQueryServiceTest {

  @Mock private TimetableReadModelRepository repository;
  @Mock private TimetableCacheService cache;

  @InjectMocks
  private TimetableQueryService service;

  @Test
  void whenCacheHit_thenReturnsCachedValueWithoutDbQuery() {
    var id = UUID.randomUUID().toString();
    var view = buildView(id, "ACTIVE");

    when(cache.get(id)).thenReturn(Optional.of(view));

    var result = service.getById(id);

    assertThat(result).isEqualTo(view);
    verifyNoInteractions(repository);
  }

  @Test
  void whenCacheMiss_thenQueriesDbAndPopulatesCache() {
    var id = UUID.randomUUID().toString();
    var entity = buildEntity(id, "ACTIVE");

    when(cache.get(id)).thenReturn(Optional.empty());
    when(repository.findById(UUID.fromString(id))).thenReturn(Optional.of(entity));

    var result = service.getById(id);

    assertThat(result.id()).isEqualTo(id);
    assertThat(result.status()).isEqualTo("ACTIVE");
    verify(cache).put(any(TimetableView.class));
  }

  @Test
  void whenTimetableNotFound_thenThrowsNoSuchElementException() {
    var id = UUID.randomUUID().toString();

    when(cache.get(id)).thenReturn(Optional.empty());
    when(repository.findById(UUID.fromString(id))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getById(id))
        .isInstanceOf(NoSuchElementException.class);
  }

  private TimetableView buildView(String id, String status) {
    return new TimetableView(id, "GWR-PAD-BRI", "Test Timetable", null,
        status, LocalDate.of(2026, 6, 1), null, "user-1", null, 1L);
  }

  private TimetableReadModelEntity buildEntity(String id, String status) {
    var entity = new TimetableReadModelEntity();
    entity.setId(UUID.fromString(id));
    entity.setLineId("GWR-PAD-BRI");
    entity.setName("Test Timetable");
    entity.setStatus(status);
    entity.setEffectiveDate(LocalDate.of(2026, 6, 1));
    entity.setAuthorId("user-1");
    entity.setVersion(1L);
    entity.setLastEventId(UUID.randomUUID().toString());
    entity.setLastUpdatedAt(Instant.now());
    return entity;
  }
}
