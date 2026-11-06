package com.dilucca.billing.api.pricing;

import org.springframework.http.HttpStatus;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/additional-price-rules")
public class AdditionalPriceRuleController {
    private final AdditionalPriceRuleQueryService query;
    private final AdditionalPriceRuleCreateService creator;

    public AdditionalPriceRuleController(AdditionalPriceRuleQueryService query, AdditionalPriceRuleCreateService creator) {
        this.query = query;
        this.creator = creator;
    }

    @GetMapping
    public PriceRulePage list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit) {
        if (page < 1 || limit < 1 || limit > 100 || (long) (page - 1) * limit > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination parameters");
        }
        return query.list(page, limit);
    }

    @PostMapping
    public ResponseEntity<String> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody Map<String, Object> body, @AuthenticationPrincipal Jwt jwt) {
        UUID key = CreatePriceRequest.key(idempotencyKey);
        var request = CreateRuleRequest.parse(body);
        UUID actor;
        try {
            actor = CreatePriceRequest.key(jwt == null ? null : jwt.getSubject());
        } catch (ResponseStatusException invalidIdentity) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid staff identity");
        }
        var result = creator.create(key, actor, request);
        return ResponseEntity.status(result.status()).header("Location", result.location())
                .contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
}
