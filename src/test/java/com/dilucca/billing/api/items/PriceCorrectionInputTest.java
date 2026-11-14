package com.dilucca.billing.api.items;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PriceCorrectionInputTest {
    private static final String VALID="{\"unitPrice\":\"37.11\",\"reason\":\" Administrative correction \",\"expectedVersion\":2}";

    @Test void preservesExactMoneyReasonAndExpectedVersion() {
        var input=PriceCorrectionInput.parse(VALID);
        assertEquals("37.11",input.unitPrice().toPlainString());
        assertEquals(" Administrative correction ",input.reason());
        assertEquals(2,input.expectedVersion());
    }

    @Test void rejectsNonCanonicalOrNonPositiveMoney() {
        for(String amount:new String[]{"0.00","-1.00","1.2","1.234","01.00","1e2","1000000000000.00"})
            assertThrows(IllegalArgumentException.class,()->PriceCorrectionInput.parse(VALID.replace("37.11",amount)));
        assertThrows(IllegalArgumentException.class,()->PriceCorrectionInput.parse(VALID.replace("\"37.11\"","37.11")));
    }

    @Test void rejectsAmbiguousJsonAndUntrustedFields() {
        for(String body:new String[]{null,"null","[]","{}",VALID+" {}",
                VALID.replace("\"expectedVersion\":2","\"expectedVersion\":2,\"expectedVersion\":3"),
                VALID.replace("\"reason\":","\"actor\":\"admin\",\"reason\":"),
                VALID.replace("37.11","x".repeat(17000))})
            assertThrows(IllegalArgumentException.class,()->PriceCorrectionInput.parse(body));
    }

    @Test void requiresBoundedReasonAndPositiveIntegerVersion() {
        for(String version:new String[]{"0","-1","2.0","\"2\"","2147483648","null"})
            assertThrows(IllegalArgumentException.class,()->PriceCorrectionInput.parse(VALID.replace(":2}",":"+version+"}")));
        for(String reason:new String[]{""," ","x".repeat(1001)})
            assertThrows(IllegalArgumentException.class,()->PriceCorrectionInput.parse(VALID.replace(" Administrative correction ",reason)));
    }
}
