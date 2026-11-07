package com.dilucca.billing.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ClinicalEventDecoderTest {
    private final ClinicalEventDecoder decoder=new ClinicalEventDecoder();
    private String fixture(String name) throws Exception {
        try(var source=getClass().getResourceAsStream("/clinical/"+name+".json")) { return new String(source.readAllBytes(),StandardCharsets.UTF_8); }
    }
    @Test void acceptsCanonicalFactsButDoesNotProjectOrLogClinicalNarrative() throws Exception {
        var event=decoder.decode(fixture("procedure"));
        assertInstanceOf(ClinicalFact.Procedure.class,event.payload());
        var procedure=(ClinicalFact.Procedure)event.payload();
        assertEquals("0.5000",procedure.extras().getFirst().quantity().toPlainString());
        assertFalse(event.toString().contains("Protected synthetic"));
        assertFalse(procedure.toString().contains("Protected synthetic"));
        assertInstanceOf(ClinicalFact.Consultation.class,decoder.decode(fixture("consultation")).payload());
        assertInstanceOf(ClinicalFact.Declaration.class,decoder.decode(fixture("declaration")).payload());
    }
    @Test void rejectsForgedEnvelopeDuplicatePropertiesAndTrailingData() throws Exception {
        String raw=fixture("procedure");
        for(String bad:new String[]{raw.replace("clinical-service","billing-service"),raw.replace("\"version\":1","\"version\":\"1\""),
                raw.replace("\"version\":1","\"version\":1,\"version\":1"),raw.replace("\"version\":1","\"version\":1,\"unexpected\":true"),
                raw.replace("\"aggregateType\":\"Treatment\"","\"aggregateType\":\"Invoice\""),raw+"{}",
                raw.replace("81000000-0000-4000-8000-000000000001","1-1-1-1-1"),
                raw.replace("\"completedAt\":\"2026-11-06T15:00:00Z\"","\"completedAt\":\"invalid\"")})
            assertThrows(ClinicalEventDecoder.Invalid.class,() -> decoder.decode(bad));
    }
    @Test void validatesExactPositiveQuantitiesSourceUniquenessAndAggregateCorrelation() throws Exception {
        String raw=fixture("procedure");
        for(String bad:new String[]{raw.replace("0.5000","0.0000"),raw.replace("0.5000","0.50001"),raw.replace("0.5000","5e-1"),
                raw.replace("\"quantity\":\"0.5000\"","\"quantity\":0.5"),
                raw.replace("000000000006","000000000003"),
                raw.replace("\"treatmentId\":\"81000000-0000-4000-8000-000000000002\"","\"treatmentId\":\"81000000-0000-4000-8000-000000000099\"")})
            assertThrows(ClinicalEventDecoder.Invalid.class,() -> decoder.decode(bad));
        String declaration=fixture("declaration");
        assertThrows(ClinicalEventDecoder.Invalid.class,() -> decoder.decode(declaration.replace("\"clinicalVersion\":3","\"clinicalVersion\":3.1")));
        assertThrows(ClinicalEventDecoder.Invalid.class,() -> decoder.decode(declaration.replace("\"procedureIds\":[\"81000000-0000-4000-8000-000000000003\"]",
                "\"procedureIds\":[\"81000000-0000-4000-8000-000000000003\",\"81000000-0000-4000-8000-000000000003\"]")));
    }
}
