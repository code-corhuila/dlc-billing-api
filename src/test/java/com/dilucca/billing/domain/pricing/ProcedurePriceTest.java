package com.dilucca.billing.domain.pricing;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProcedurePriceTest {

    @Test
    void acceptsPositivePriceWithTwoDecimals() {
        BigDecimal amount = ProcedurePrice.parseAmount("150000.00");

        assertEquals(new BigDecimal("150000.00"), amount);
        assertEquals(2, amount.scale());
    }

    @Test
    void rejectsZeroAndNegativePrices() {
        assertThrows(IllegalArgumentException.class,
                () -> ProcedurePrice.parseAmount("0.00"));

        assertThrows(IllegalArgumentException.class,
                () -> ProcedurePrice.parseAmount("-100.00"));
    }

    @Test
    void rejectsInvalidDecimalPrecision() {
        assertThrows(IllegalArgumentException.class,
                () -> ProcedurePrice.parseAmount("150000"));

        assertThrows(IllegalArgumentException.class,
                () -> ProcedurePrice.parseAmount("150000.999"));
    }

    @Test
    void rejectsAmountsExceedingDatabasePrecision() {
        assertThrows(IllegalArgumentException.class,
                () -> ProcedurePrice.parseAmount("1234567890123.00"));
    }

    @Test
    void rejectsMalformedAmounts() {
        assertThrows(IllegalArgumentException.class,
                () -> ProcedurePrice.parseAmount("invalid"));

        assertThrows(IllegalArgumentException.class,
                () -> ProcedurePrice.parseAmount(null));
    }
}
