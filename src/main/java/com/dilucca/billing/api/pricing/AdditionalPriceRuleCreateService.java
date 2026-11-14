package com.dilucca.billing.api.pricing;

import com.dilucca.billing.domain.pricing.AdditionalPriceRuleCatalog;
import java.util.UUID;

public interface AdditionalPriceRuleCreateService {
    CreatedResource create(UUID key, UUID actor, AdditionalPriceRuleCatalog request);
}
