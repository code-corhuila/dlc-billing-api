package com.dilucca.billing.api.items;

import com.dilucca.billing.infrastructure.persistence.JdbcBillableItemQueryRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/billable-items")
public class BillableItemController {
    private final JdbcBillableItemQueryRepository repository;

    public BillableItemController(JdbcBillableItemQueryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public BillableItemPage list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit,
            @RequestParam(required=false) String patientId,
            @RequestParam(required=false) String procedureId) {
        if (page < 1 || limit < 1 || limit > 100 || (long)(page-1)*limit > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination parameters");
        }
        return repository.list(page, limit, identifier(patientId), identifier(procedureId));
    }

    private UUID identifier(String value) {
        if (value == null) return null;
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) return id;
        } catch (IllegalArgumentException invalid) { /* Use the same safe rejection below. */ }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid identifier");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("error", "SERVICE_UNAVAILABLE",
                "message", "Billing persistence is unavailable"));
    }

    @ExceptionHandler({ResponseStatusException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalidQuery() {
        return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_ERROR",
                "message", "Invalid billable item query"));
    }
}
