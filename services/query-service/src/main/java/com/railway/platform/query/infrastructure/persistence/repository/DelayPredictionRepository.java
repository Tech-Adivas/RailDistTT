package com.railway.platform.query.infrastructure.persistence.repository;

import com.railway.platform.query.infrastructure.persistence.entity.DelayPredictionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link DelayPredictionEntity}.
 *
 * <p>The {@code existsByEventId} method is used for idempotency checking in
 * {@code PredictionQueryProjector} before inserting a new projection row.
 */
public interface DelayPredictionRepository extends JpaRepository<DelayPredictionEntity, UUID> {

  boolean existsByEventId(String eventId);

  List<DelayPredictionEntity> findByRouteIdOrderByPredictedAtDesc(String routeId);

  List<DelayPredictionEntity> findByRouteIdAndTrainIdOrderByPredictedAtDesc(
      String routeId, String trainId);

  Optional<DelayPredictionEntity> findFirstByRouteIdOrderByPredictedAtDesc(String routeId);
}
