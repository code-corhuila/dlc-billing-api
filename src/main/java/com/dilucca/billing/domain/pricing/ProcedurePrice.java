package com.dilucca.billing.domain.pricing;

import java.math.BigDecimal;
import java.util.regex.Pattern;

public final class ProcedurePrice {

    private static final Pattern MONEY_PATTERN =
            Pattern.compile("^[0-9]{1,12}\\.[0-9]{2}$");

    private ProcedurePrice() {
    }

    public static BigDecimal parseAmount(String value) {
        if (value == null || !MONEY_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Price must be a decimal string with exactly two digits "
                            + "and at most twelve integer digits");
        }

        BigDecimal amount = new BigDecimal(value);

        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Procedure price must be positive");
        }

        return amount;
    }
}
