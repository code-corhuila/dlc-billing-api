package com.dilucca.billing.api.manual;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManualChargeInputTest {
    private final UUID patient=UUID.randomUUID(),appointment=UUID.randomUUID(),procedure=UUID.randomUUID(),source=UUID.randomUUID();
    private String valid() {
        return "{\"patientId\":\""+patient+"\",\"appointmentId\":\""+appointment+"\",\"procedureId\":\""+procedure
                +"\",\"sourceRecordId\":\""+source+"\",\"itemType\":\"ADDITIONAL_MATERIAL\",\"quantity\":\"1.2345\","
                +"\"unitPrice\":\"12.34\",\"currency\":\"COP\",\"concept\":\" Exact manual concept \",\"justification\":\" Owner reason \"}";
    }
    @Test void preservesExactManualValueAndTextWithoutCatalogSubstitution() {
        var input=ManualChargeInput.parse(valid());
        assertEquals(patient,input.patientId()); assertEquals(source,input.sourceRecordId());
        assertEquals("1.2345",input.quantity().toPlainString()); assertEquals("12.34",input.unitPrice().toPlainString());
        assertEquals(" Exact manual concept ",input.concept()); assertEquals(" Owner reason ",input.justification());
        assertEquals("15.23",input.lineTotal().toPlainString());
    }
    @Test void rejectsAmbiguousJsonAndUntrustedActorOrTimeFields() {
        String good=valid();
        for(String invalid:new String[]{"null","[]","{}",good+" {}",good.replace("\"currency\":\"COP\"","\"currency\":\"COP\",\"currency\":\"COP\""),
                good.replace("\"currency\":\"COP\"","\"currency\":\"COP\",\"recordedBy\":\""+patient+"\""),
                good.replace("\"currency\":\"COP\"","\"currency\":\"COP\",\"recordedAt\":\"2026-11-07T00:00:00Z\""),
                good.replace(source.toString(),"1-1-1-1-1")})
            assertThrows(IllegalArgumentException.class,() -> ManualChargeInput.parse(invalid));
    }
    @Test void rejectsInvalidMoneyQuantityCurrencyAndBlankReasons() {
        String good=valid();
        for(String amount:new String[]{"0.00","-1.00","1.234","1e2","01.00","1000000000000.00"})
            assertThrows(IllegalArgumentException.class,() -> ManualChargeInput.parse(good.replace("12.34",amount)));
        for(String quantity:new String[]{"0","0.0000","-1","1.23456","1e2","01","NaN"})
            assertThrows(IllegalArgumentException.class,() -> ManualChargeInput.parse(good.replace("1.2345",quantity)));
        for(String invalid:new String[]{good.replace("COP","USD"),good.replace("ADDITIONAL_MATERIAL","UNKNOWN"),
                good.replace(" Owner reason "," "),good.replace(" Exact manual concept "," "),
                good.replace(" Owner reason ","x".repeat(1001)),good.replace(" Exact manual concept ","x".repeat(501)),
                good.replace("\"12.34\"","12.34"),good.replace("1.2345","999999999999999999999999999999")})
            assertThrows(IllegalArgumentException.class,() -> ManualChargeInput.parse(invalid));
    }
}
