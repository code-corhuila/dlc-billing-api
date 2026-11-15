package com.dilucca.billing.api.items;

import java.math.BigDecimal;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Administrative correction only; actor identity is never accepted from input. */
public record PriceCorrectionInput(BigDecimal unitPrice,String reason,int expectedVersion) {
    private static final JsonMapper PARSER=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public PriceCorrectionInput {
        if(unitPrice==null || unitPrice.signum()<=0 || unitPrice.scale()!=2
                || unitPrice.compareTo(new BigDecimal("999999999999.99"))>0
                || reason==null || reason.isBlank() || reason.length()>1000 || expectedVersion<1)
            throw new IllegalArgumentException("Invalid price correction");
    }

    public static PriceCorrectionInput parse(String body) {
        try {
            if(body==null || body.length()>16384) throw new IllegalArgumentException();
            var n=PARSER.readTree(body);
            if(n==null || !n.isObject() || n.size()!=3) throw new IllegalArgumentException();
            var price=n.get("unitPrice"); var reason=n.get("reason"); var version=n.get("expectedVersion");
            if(price==null || !price.isString() || !price.stringValue().matches("(?:0|[1-9][0-9]{0,11})\\.[0-9]{2}")
                    || reason==null || !reason.isString() || version==null || !version.isIntegralNumber()
                    || !version.canConvertToInt()) throw new IllegalArgumentException();
            return new PriceCorrectionInput(new BigDecimal(price.stringValue()),reason.stringValue(),version.intValue());
        } catch(Exception invalid) { throw new IllegalArgumentException("Invalid price correction"); }
    }
}
