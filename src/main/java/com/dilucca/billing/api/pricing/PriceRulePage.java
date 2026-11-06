package com.dilucca.billing.api.pricing;

import java.util.List;
import java.util.Objects;

public record PriceRulePage(List<PriceRule> data, PaginatedMeta meta) {
    public PriceRulePage {
        data = List.copyOf(data);
        Objects.requireNonNull(meta);
    }
}
