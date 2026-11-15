package com.dilucca.billing.api.pricing;

import java.util.List;
import java.util.Objects;

public record PricePage(
        List<Price> data,
        PaginatedMeta meta) {

    public PricePage {
        Objects.requireNonNull(data, "Data must not be null");
        Objects.requireNonNull(meta, "Metadata must not be null");

        data = List.copyOf(data);
    }
}
