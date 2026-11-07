package com.dilucca.billing.api.invoices;

import com.dilucca.billing.domain.invoicing.*;
import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceIssuance;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/invoices/{id}/issuances")
public class InvoiceIssuanceController {
    private final JdbcInvoiceIssuance issuance;
    private final JsonMapper parser=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public InvoiceIssuanceController(JdbcInvoiceIssuance issuance) { this.issuance=issuance; }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> issue(@PathVariable String id,@RequestHeader("Idempotency-Key") List<String> keys,
            @RequestHeader(value="X-Correlation-Id",required=false) String correlation,
            @RequestBody String body,@AuthenticationPrincipal Jwt jwt) {
        UUID invoice=identifier(id);
        if (keys.size()!=1) throw new IllegalArgumentException();
        UUID key=identifier(keys.getFirst());
        long version=version(body);
        UUID actor;
        try { actor=identifier(jwt==null ? null : jwt.getSubject()); }
        catch (IllegalArgumentException invalidIdentity) {
            return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"UNAUTHORIZED\",\"message\":\"Invalid staff identity\"}");
        }
        UUID trace;
        try { trace=identifier(correlation); }
        catch (IllegalArgumentException arbitraryTrace) {
            trace=correlation==null ? UUID.randomUUID() : UUID.nameUUIDFromBytes(correlation.getBytes(StandardCharsets.UTF_8));
        }
        var result=issuance.issue(key,actor,invoice,version,trace);
        return ResponseEntity.status(result.status()).header("Location",result.location())
                .contentType(MediaType.APPLICATION_JSON).body(result.body());
    }
    private long version(String body) {
        try {
            var node=parser.readTree(body);
            if (node==null || !node.isObject() || node.size()!=1 || !node.has("expectedVersion")
                    || !node.get("expectedVersion").isIntegralNumber() || !node.get("expectedVersion").canConvertToLong()
                    || node.get("expectedVersion").longValue()<1) throw new IllegalArgumentException();
            return node.get("expectedVersion").longValue();
        } catch (Exception invalid) { throw new IllegalArgumentException(); }
    }
    private static UUID identifier(String value) {
        if (value==null) throw new IllegalArgumentException();
        UUID id=UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return id;
    }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MissingRequestHeaderException.class})
    ResponseEntity<?> invalid() { return error(400,"VALIDATION_ERROR","Invalid invoice issuance request"); }
    @ExceptionHandler(InvoiceIssuanceMissing.class)
    ResponseEntity<?> missing() { return error(404,"NOT_FOUND","Invoice not found"); }
    @ExceptionHandler(InvoiceIssuanceConflict.class)
    ResponseEntity<?> conflict(InvoiceIssuanceConflict failure) { return error(409,failure.code(),failure.getMessage()); }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    ResponseEntity<?> unavailable() { return error(503,"SERVICE_UNAVAILABLE","Invoice issuance unavailable"); }
    private ResponseEntity<?> error(int status,String code,String message) {
        return ResponseEntity.status(status).body(Map.of("error",code,"message",message));
    }
}
