package com.dilucca.billing.api.pricing;

import com.dilucca.billing.domain.pricing.AdditionalPriceRuleCatalog;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class CreateRuleRequest {
    private static final Set<String> FIELDS = Set.of("code", "itemType", "unitPrice", "requiresApproval", "validFrom");

    private CreateRuleRequest() { }

    public static AdditionalPriceRuleCatalog parse(Map<String, Object> body) {
        if (body == null || !body.keySet().equals(FIELDS) || !Boolean.FALSE.equals(body.get("requiresApproval"))
                || !(body.get("code") instanceof String) || !(body.get("itemType") instanceof String)
                || !(body.get("unitPrice") instanceof String) || !(body.get("validFrom") instanceof String)) {
            throw invalid();
        }
        try {
            return new AdditionalPriceRuleCatalog((String) body.get("code"), (String) body.get("itemType"),
                    (String) body.get("unitPrice"), Instant.parse((String) body.get("validFrom")));
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            throw invalid();
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid additional price rule");
    }
}
