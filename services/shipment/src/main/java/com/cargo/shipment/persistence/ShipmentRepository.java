package com.cargo.shipment.persistence;

import com.cargo.shipment.domain.ShipmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.lang.Nullable;

import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<ShipmentEntity, UUID> {

    Optional<ShipmentEntity> findByTrackingCode(String trackingCode);

    /**
     * Paginated list with optional filters. A {@code null} for either
     * parameter means "no filter on that column."
     */
    @Query("""
            SELECT s FROM ShipmentEntity s
            WHERE (:status IS NULL OR s.status = :status)
              AND (:carrier IS NULL OR s.carrier = :carrier)
            """)
    Page<ShipmentEntity> findByFilters(
            @Param("status") @Nullable ShipmentStatus status,
            @Param("carrier") @Nullable String carrier,
            Pageable pageable);
}
