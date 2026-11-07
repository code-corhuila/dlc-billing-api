package com.dilucca.billing.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InitialPaymentMinimumTest {
    private BigDecimal minimum(String total,String balance) {
        return InitialPaymentMinimum.forBalance(new BigDecimal(total),new BigDecimal(balance));
    }
    @Test void firstPaymentRoundsUpExactlyIncludingOddCentsAndMaximumMoney() {
        assertEquals(new BigDecimal("5.01"),minimum("10.01","10.01"));
        assertEquals(new BigDecimal("5.00"),minimum("10.00","10.00"));
        assertEquals(new BigDecimal("0.01"),minimum("0.01","0.01"));
        assertEquals(new BigDecimal("500000000000.00"),minimum("999999999999.99","999999999999.99"));
    }
    @Test void laterPaymentsMayBeOneCentEvenForOlderInvoices() {
        assertEquals(new BigDecimal("0.01"),minimum("10.01","9.01"));
    }
    @Test void rejectsInconsistentAmounts() {
        assertThrows(IllegalArgumentException.class,() -> minimum("10.00","10.01"));
        assertThrows(IllegalArgumentException.class,() -> minimum("-1.00","0.00"));
    }
}
