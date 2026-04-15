package com.cargo.shipment.domain;

/**
 * Immutable address value object used by the domain layer. Mapped
 * to/from {@code cargo.common.v1.Address} at the API boundary.
 */
public record Address(String line1, String city, String country, String postalCode) {
    public Address {
        if (isBlank(line1)) throw new IllegalArgumentException("address line1 is required");
        if (isBlank(city)) throw new IllegalArgumentException("address city is required");
        if (isBlank(country)) throw new IllegalArgumentException("address country is required");
        if (isBlank(postalCode)) throw new IllegalArgumentException("address postal_code is required");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
