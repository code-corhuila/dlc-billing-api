package com.dilucca.billing.domain.pricing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PriceValidityTest {

    private static final Instant JAN_1 =
            Instant.parse("2026-01-01T00:00:00Z");

    private static final Instant FEB_1 =
            Instant.parse("2026-02-01T00:00:00Z");

    private static final Instant MAR_1 =
            Instant.parse("2026-03-01T00:00:00Z");

    @Test
    void acceptsValidDateRange() {
        assertDoesNotThrow(() -> new PriceValidity(JAN_1, FEB_1));
    }

    @Test
    void acceptsOpenEndedValidity() {
        assertDoesNotThrow(() -> new PriceValidity(JAN_1, null));
    }

    @Test
    void rejectsEndBeforeStart() {
        assertThrows(IllegalArgumentException.class,
                () -> new PriceValidity(FEB_1, JAN_1));
    }

    @Test
    void rejectsEqualStartAndEnd() {
        assertThrows(IllegalArgumentException.class,
                () -> new PriceValidity(JAN_1, JAN_1));
    }

    @Test
    void detectsOverlappingPeriods() {
        PriceValidity first = new PriceValidity(JAN_1, MAR_1);
        PriceValidity second = new PriceValidity(FEB_1, null);

        assertTrue(first.overlaps(second));
        assertTrue(second.overlaps(first));
    }

    @Test
    void acceptsAdjacentPeriodsWithoutOverlap() {
        PriceValidity first = new PriceValidity(JAN_1, FEB_1);
        PriceValidity second = new PriceValidity(FEB_1, MAR_1);

        assertFalse(first.overlaps(second));
        assertFalse(second.overlaps(first));
    }

    @Test
    void rejectsMissingStartDate() {
        assertThrows(IllegalArgumentException.class,
                () -> new PriceValidity(null, FEB_1));
    }
}
