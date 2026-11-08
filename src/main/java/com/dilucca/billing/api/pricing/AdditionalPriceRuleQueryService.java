package com.dilucca.billing.api.pricing;

public interface AdditionalPriceRuleQueryService {
    PriceRulePage list(int page, int limit);
}
