package com.railway.platform.query.infrastructure.persistence.repository;

import com.railway.platform.query.infrastructure.persistence.entity.TimetableReadModelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TimetableReadModelRepository extends JpaRepository<TimetableReadModelEntity, UUID> {
  List<TimetableReadModelEntity> findByLineIdOrderByEffectiveDateDesc(String lineId);
  List<TimetableReadModelEntity> findByLineIdAndStatusOrderByEffectiveDateDesc(String lineId, String status);
}
