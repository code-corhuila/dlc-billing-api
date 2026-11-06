package com.dilucca.billing.api.pricing;

import com.dilucca.billing.domain.pricing.ProcedurePriceCatalog;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class CreatePriceRequest {
    private static final Set<String> FIELDS =
            Set.of("procedureCode", "name", "basePrice", "validFrom");

    private CreatePriceRequest() { }

    public static UUID key(String value) {
        if (value == null || !value.matches(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw invalid();
        }
        return UUID.fromString(value);
    }

    public static ProcedurePriceCatalog parse(Map<String, Object> body) {
        if (body == null || !body.keySet().equals(FIELDS)
                || body.values().stream().anyMatch(value -> !(value instanceof String))) {
            throw invalid();
        }
        try {
            return new ProcedurePriceCatalog((String) body.get("procedureCode"),
                    (String) body.get("name"), (String) body.get("basePrice"),
                    Instant.parse((String) body.get("validFrom")));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid price creation request");
    }
}
