package com.dilucca.billing.api.encounters;

import com.dilucca.billing.infrastructure.persistence.JdbcPendingPriceQuery;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** GET /api/v1/encounter-accounts/pending-prices (BillingListpendingpriceencounters, ADR-015 decision 6). */
@RestController
@RequestMapping("/api/v1/encounter-accounts/pending-prices")
public class PendingPriceController {
    private final JdbcPendingPriceQuery query;

    public PendingPriceController(JdbcPendingPriceQuery query) {
        this.query = query;
    }

    @GetMapping
    public ResponseEntity<JdbcPendingPriceQuery.Page> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit) {
        if (page < 1 || limit < 1 || limit > 100 || (long)(page-1)*limit > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination parameters");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(query.list(page, limit));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("error", "SERVICE_UNAVAILABLE",
                "message", "Billing persistence is unavailable"));
    }

    @ExceptionHandler({ResponseStatusException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalidQuery() {
        return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_ERROR",
                "message", "Invalid pending price query"));
    }
}
