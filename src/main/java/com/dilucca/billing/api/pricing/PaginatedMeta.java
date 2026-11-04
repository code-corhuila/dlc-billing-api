package com.dilucca.billing.api.pricing;

public record PaginatedMeta(
        int page,
        int limit,
        long total,
        int totalPages) {

    public PaginatedMeta {
        if (page < 1) {
            throw new IllegalArgumentException(
                    "Page must be greater than or equal to 1");
        }

        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "Limit must be between 1 and 100");
        }

        if (total < 0) {
            throw new IllegalArgumentException(
                    "Total must not be negative");
        }

        if (totalPages < 0) {
            throw new IllegalArgumentException(
                    "Total pages must not be negative");
        }
    }
}
