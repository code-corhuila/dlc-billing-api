package com.dilucca.billing.api.pricing;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = ProcedurePriceController.class)
public class PriceCreationErrors {
    @ExceptionHandler(PriceCreationConflict.class)
    ResponseEntity<?> conflict(PriceCreationConflict error) {
        return ResponseEntity.status(409).body(Map.of("error", error.code(), "message", error.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class})
    ResponseEntity<?> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("error", "VALIDATION_ERROR",
                "message", "Invalid price creation request"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("error",
                error.getStatusCode().value() == 401 ? "UNAUTHORIZED" : "VALIDATION_ERROR",
                "message", error.getReason() == null ? "Request rejected" : error.getReason()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<?> unavailable(Exception error) {
        return ResponseEntity.status(503).body(Map.of("error", "SERVICE_UNAVAILABLE",
                "message", "Billing persistence is unavailable"));
    }
}
