package com.dilucca.billing.domain.pricing;

import java.time.Instant;
import java.util.Objects;

public final class PriceValidity {

    private final Instant validFrom;
    private final Instant validTo;

    public PriceValidity(Instant validFrom, Instant validTo) {
        if (validFrom == null) {
            throw new IllegalArgumentException(
                    "validFrom is required");
        }

        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException(
                    "validTo must be after validFrom");
        }

        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Instant validTo() {
        return validTo;
    }

    public boolean overlaps(PriceValidity other) {
        Objects.requireNonNull(other, "other validity is required");

        boolean startsBeforeOtherEnds =
                other.validTo == null
                        || validFrom.isBefore(other.validTo);

        boolean otherStartsBeforeThisEnds =
                validTo == null
                        || other.validFrom.isBefore(validTo);

        return startsBeforeOtherEnds && otherStartsBeforeThisEnds;
    }
}
