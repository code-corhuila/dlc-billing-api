package com.dilucca.billing.infrastructure.persistence;

import com.dilucca.billing.api.pricing.CreatedResource;
import com.dilucca.billing.api.pricing.PriceCreationConflict;
import com.dilucca.billing.api.pricing.ProcedurePriceCreateService;
import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JdbcProcedurePriceCreationService implements ProcedurePriceCreateService {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public JdbcProcedurePriceCreationService(JdbcTemplate jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public CreatedResource create(UUID key, UUID actor, ProcedurePriceCatalog request) {
        return new JdbcIdempotentCreation(jdbc, mapper).execute(key, actor, "BillingCreatepriceversion",
                request.procedureCode(), List.of(request.procedureCode(), request.name(),
                        request.basePrice().toPlainString(), request.validity().validFrom().toString()), now -> {
                    UUID id = UUID.randomUUID();
                    try {
                        new JdbcProcedurePriceRepository(jdbc).saveNextVersion(id, request, actor, now);
                    } catch (IllegalArgumentException conflict) {
                        throw new PriceCreationConflict("CONFLICT");
                    }
                    var price = jdbc.queryForObject("SELECT * FROM billing.procedure_prices WHERE price_id = ?",
                            JdbcProcedurePriceQueryRepository.PRICE_MAPPER, id);
                    return new CreatedResource(201, "/api/v1/procedure-prices/" + id,
                            mapper.writeValueAsString(price));
                });
    }
}
