package com.dilucca.billing.domain.pricing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProcedurePriceCatalogTest {

    private static final Instant VALID_FROM =
            Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void acceptsValidProcedurePrice() {
        assertDoesNotThrow(() -> new ProcedurePriceCatalog(
                "PROC-001", "Dental cleaning", "150000.00", VALID_FROM));
    }

    @Test
    void acceptsMaximumFieldLengths() {
        assertDoesNotThrow(() -> new ProcedurePriceCatalog(
                "P".repeat(80), "N".repeat(255),
                "150000.00", VALID_FROM));
    }

    @Test
    void rejectsMissingProcedureCode() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcedurePriceCatalog(
                        "", "Dental cleaning", "150000.00", VALID_FROM));
    }

    @Test
    void rejectsProcedureCodeExceedingLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcedurePriceCatalog(
                        "P".repeat(81), "Dental cleaning",
                        "150000.00", VALID_FROM));
    }

    @Test
    void rejectsInvalidProcedureName() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcedurePriceCatalog(
                        "PROC-001", "", "150000.00", VALID_FROM));

        assertThrows(IllegalArgumentException.class,
                () -> new ProcedurePriceCatalog(
                        "PROC-001", "N".repeat(256),
                        "150000.00", VALID_FROM));
    }

    @Test
    void rejectsInvalidBasePrice() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcedurePriceCatalog(
                        "PROC-001", "Dental cleaning",
                        "0.00", VALID_FROM));
    }

    @Test
    void rejectsMissingValidityStart() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcedurePriceCatalog(
                        "PROC-001", "Dental cleaning",
                        "150000.00", null));
    }
}
