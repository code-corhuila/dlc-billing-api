package com.dilucca.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Policy checked inside the locked invoice transaction, after idempotent replay. */
public final class InitialPaymentMinimum {
    private InitialPaymentMinimum() { }
    public static BigDecimal forBalance(BigDecimal total, BigDecimal balance) {
        if (total == null || balance == null || total.signum() < 0 || balance.signum() < 0
                || balance.compareTo(total) > 0) throw new IllegalArgumentException("Invalid invoice amounts");
        return total.compareTo(balance) > 0 ? new BigDecimal("0.01")
                : total.divide(new BigDecimal("2"), 2, RoundingMode.CEILING).max(new BigDecimal("0.01"));
    }
}
