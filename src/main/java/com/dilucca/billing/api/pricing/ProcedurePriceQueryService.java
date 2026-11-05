package com.dilucca.billing.api.pricing;

public interface ProcedurePriceQueryService {

    PricePage list(int page, int limit);
}
