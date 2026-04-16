package com.cargo.tracking.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ShipmentReadModelRepository
        extends JpaRepository<ShipmentReadModelEntity, UUID> {
}
