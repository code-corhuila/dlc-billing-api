package com.dilucca.billing.api.manual;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validated official request; actor/time are always derived by the server. */
public record ManualChargeInput(UUID patientId,UUID appointmentId,UUID procedureId,UUID sourceRecordId,
        String itemType,BigDecimal quantity,BigDecimal unitPrice,String currency,String concept,String justification) {
    private static final Set<String> TYPES=Set.of("BASE_PROCEDURE","ADDITIONAL_MATERIAL","ADDITIONAL_PROCEDURE",
            "COMPLEXITY_ADJUSTMENT","OTHER_AUTHORIZED_CHARGE");
    private static final BigDecimal MAX=new BigDecimal("999999999999.99");
    private static final JsonMapper PARSER=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public ManualChargeInput {
        if(patientId==null || appointmentId==null || procedureId==null || sourceRecordId==null
                || itemType==null || !TYPES.contains(itemType) || !"COP".equals(currency) || quantity==null || quantity.signum()<=0
                || quantity.scale()>4 || unitPrice==null || unitPrice.signum()<=0 || unitPrice.scale()!=2
                || unitPrice.compareTo(MAX)>0 || quantity.multiply(unitPrice).setScale(2,RoundingMode.HALF_UP).compareTo(MAX)>0
                || concept==null || concept.isBlank() || concept.length()>500
                || justification==null || justification.isBlank() || justification.length()>1000)
            throw new IllegalArgumentException("Invalid manual charge request");
    }
    public BigDecimal lineTotal() { return quantity.multiply(unitPrice).setScale(2,RoundingMode.HALF_UP); }
    public static ManualChargeInput parse(String body) {
        try {
            if(body==null || body.length()>16384) throw new IllegalArgumentException();
            var n=PARSER.readTree(body);
            if(n==null || !n.isObject() || n.size()!=10) throw new IllegalArgumentException();
            String quantity=text(n,"quantity"),price=text(n,"unitPrice");
            if(!quantity.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,4})?")
                    || !price.matches("(?:0|[1-9][0-9]{0,11})\\.[0-9]{2}")) throw new IllegalArgumentException();
            return new ManualChargeInput(id(n,"patientId"),id(n,"appointmentId"),id(n,"procedureId"),id(n,"sourceRecordId"),
                    text(n,"itemType"),new BigDecimal(quantity),new BigDecimal(price),text(n,"currency"),text(n,"concept"),text(n,"justification"));
        } catch(Exception invalid) { throw new IllegalArgumentException("Invalid manual charge request"); }
    }
    private static String text(JsonNode n,String field) {
        var value=n.get(field);
        if(value==null || !value.isString()) throw new IllegalArgumentException();
        return value.stringValue();
    }
    private static UUID id(JsonNode n,String field) {
        String value=text(n,field); UUID id=UUID.fromString(value);
        if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return id;
    }
}
