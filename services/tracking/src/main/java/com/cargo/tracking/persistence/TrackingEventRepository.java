package com.cargo.tracking.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrackingEventRepository extends JpaRepository<TrackingEventEntity, UUID> {

    List<TrackingEventEntity> findByShipmentIdOrderByRecordedAtDesc(
            UUID shipmentId, Pageable pageable);

    Optional<TrackingEventEntity> findFirstByShipmentIdOrderByRecordedAtDesc(UUID shipmentId);
}
