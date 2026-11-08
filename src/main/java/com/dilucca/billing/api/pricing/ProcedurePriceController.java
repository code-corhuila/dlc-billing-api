package com.dilucca.billing.api.pricing;

import java.util.Objects;
import java.util.UUID;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/procedure-prices")
public class ProcedurePriceController {

    private final ProcedurePriceQueryService queryService;

    public ProcedurePriceController(
            ProcedurePriceQueryService queryService) {
        this.queryService = Objects.requireNonNull(queryService);
    }

    @GetMapping
    public PricePage list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {

        if (page < 1 || limit < 1 || limit > 100
                || (long) (page - 1) * limit > Integer.MAX_VALUE) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid pagination parameters");
        }

        return queryService.list(page, limit);
    }

    @PostMapping
    public void create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body) {

        CreatePriceRequest.key(idempotencyKey);
        CreatePriceRequest.parse(body);

        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Price creation is not available");
    }
}
