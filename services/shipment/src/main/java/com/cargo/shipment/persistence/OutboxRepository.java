package com.cargo.shipment.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {
}
