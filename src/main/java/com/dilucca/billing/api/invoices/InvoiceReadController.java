package com.dilucca.billing.api.invoices;

import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceQueryRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceReadController {
    private final JdbcInvoiceQueryRepository repository;
    public InvoiceReadController(JdbcInvoiceQueryRepository repository) { this.repository=repository; }

    @GetMapping
    public InvoicePage list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int limit, @RequestParam(required=false) String patientId) {
        if (page < 1 || limit < 1 || limit > 100 || (long)(page-1)*limit > Integer.MAX_VALUE)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return repository.list(page,limit,identifier(patientId));
    }

    @GetMapping("/{id}")
    public Invoice read(@PathVariable String id) {
        return repository.find(identifier(id)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private UUID identifier(String value) {
        if (value == null) return null;
        try {
            UUID id=UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) return id;
        } catch (IllegalArgumentException invalid) { /* Reject below without exposing input. */ }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException error) {
        boolean missing=error.getStatusCode().value()==404;
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",missing ? "NOT_FOUND" : "VALIDATION_ERROR",
                "message",missing ? "Invoice not found" : "Invalid invoice query"));
    }
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<?> invalid() { return status(new ResponseStatusException(HttpStatus.BAD_REQUEST)); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("error","SERVICE_UNAVAILABLE",
                "message","Billing persistence is unavailable"));
    }
}
