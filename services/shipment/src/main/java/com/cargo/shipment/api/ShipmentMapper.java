package com.cargo.shipment.api;

import com.cargo.common.v1.Address;
import com.cargo.shipment.persistence.ShipmentEntity;
import com.cargo.shipment.v1.Shipment;
import com.cargo.shipment.v1.ShipmentStatus;
import com.google.protobuf.Timestamp;

import java.time.Instant;

/**
 * Protobuf ↔ domain mapping for the Shipment aggregate. Lives at the
 * API edge so the domain stays free of wire types.
 */
final class ShipmentMapper {

    private ShipmentMapper() {}

    static com.cargo.shipment.domain.Address toDomain(Address proto) {
        if (proto == null) return null;
        return new com.cargo.shipment.domain.Address(
                proto.getLine1(),
                proto.getCity(),
                proto.getCountry(),
                proto.getPostalCode());
    }

    static Shipment toProto(ShipmentEntity entity) {
        Shipment.Builder builder = Shipment.newBuilder()
                .setId(entity.getId().toString())
                .setTrackingCode(entity.getTrackingCode())
                .setOrigin(addressProto(
                        entity.getOriginLine1(),
                        entity.getOriginCity(),
                        entity.getOriginCountry(),
                        entity.getOriginPostalCode()))
                .setDestination(addressProto(
                        entity.getDestinationLine1(),
                        entity.getDestinationCity(),
                        entity.getDestinationCountry(),
                        entity.getDestinationPostalCode()))
                .setCarrier(entity.getCarrier())
                .setStatus(toProto(entity.getStatus()))
                .setWeightKg(entity.getWeightKg())
                .setCreatedAt(toProto(entity.getCreatedAt()))
                .setUpdatedAt(toProto(entity.getUpdatedAt()));
        if (entity.getEta() != null) {
            builder.setEta(toProto(entity.getEta()));
        }
        return builder.build();
    }

    private static Address addressProto(String line1, String city, String country, String postalCode) {
        return Address.newBuilder()
                .setLine1(line1)
                .setCity(city)
                .setCountry(country)
                .setPostalCode(postalCode)
                .build();
    }

    private static ShipmentStatus toProto(com.cargo.shipment.domain.ShipmentStatus domain) {
        return switch (domain) {
            case CREATED -> ShipmentStatus.SHIPMENT_STATUS_CREATED;
            case IN_TRANSIT -> ShipmentStatus.SHIPMENT_STATUS_IN_TRANSIT;
            case DELIVERED -> ShipmentStatus.SHIPMENT_STATUS_DELIVERED;
            case CANCELLED -> ShipmentStatus.SHIPMENT_STATUS_CANCELLED;
        };
    }

    private static Timestamp toProto(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
