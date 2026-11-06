package com.dilucca.billing.api.pricing;

import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.util.UUID;

public interface ProcedurePriceCreateService {
    CreatedPrice create(UUID key, UUID actor, ProcedurePriceCatalog request);

    record CreatedPrice(int status, String location, String body) { }
}
