package com.dilucca.billing.api.pricing;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/additional-price-rules")
public class AdditionalPriceRuleController {
    private final AdditionalPriceRuleQueryService query;

    public AdditionalPriceRuleController(AdditionalPriceRuleQueryService query) {
        this.query = query;
    }

    @GetMapping
    public PriceRulePage list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        if (page < 1 || limit < 1 || limit > 100 || (long) (page - 1) * limit > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination parameters");
        }
        return query.list(page, limit);
    }
}
