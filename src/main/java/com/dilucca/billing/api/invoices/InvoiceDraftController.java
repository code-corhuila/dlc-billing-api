package com.dilucca.billing.api.invoices;

import com.dilucca.billing.domain.invoicing.*;
import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceDraftCreation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceDraftController {
    private final JdbcInvoiceDraftCreation drafts;
    private final JsonMapper parser = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public InvoiceDraftController(JdbcInvoiceDraftCreation drafts) { this.drafts=drafts; }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(@RequestHeader("Idempotency-Key") List<String> keys,
            @RequestBody String body, @AuthenticationPrincipal Jwt jwt) {
        if (keys.size()!=1) throw new IllegalArgumentException();
        UUID key=identifier(keys.getFirst());
        var command=parse(body);
        UUID actor;
        try { actor=identifier(jwt==null ? null : jwt.getSubject()); }
        catch (IllegalArgumentException invalidIdentity) {
            return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"UNAUTHORIZED\",\"message\":\"Invalid staff identity\"}");
        }
        var result=drafts.create(key,actor,command);
        return ResponseEntity.status(result.status()).header("Location",result.location())
                .contentType(MediaType.APPLICATION_JSON).body(result.body());
    }

    private InvoiceDraft parse(String body) {
        try {
            var node=parser.readTree(body);
            if (node==null || !node.isObject() || !node.has("patientId") || !node.has("billableItemIds")
                    || !(node.size()==2 || node.size()==3 && node.has("appointmentId"))
                    || !node.get("billableItemIds").isArray()) throw new IllegalArgumentException();
            List<UUID> items=new ArrayList<>();
            for (JsonNode item:node.get("billableItemIds")) items.add(identifier(text(item)));
            return new InvoiceDraft(identifier(text(node.get("patientId"))),
                    node.has("appointmentId") ? identifier(text(node.get("appointmentId"))) : null,items);
        } catch (Exception invalid) { throw new IllegalArgumentException(); }
    }

    private static String text(JsonNode node) {
        if (node==null || !node.isString()) throw new IllegalArgumentException();
        return node.stringValue();
    }
    private static UUID identifier(String value) {
        if (value==null) throw new IllegalArgumentException();
        UUID id=UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return id;
    }

    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MissingRequestHeaderException.class})
    ResponseEntity<?> invalid() { return error(400,"VALIDATION_ERROR","Invalid invoice draft request"); }
    @ExceptionHandler(InvoiceDraftConflict.class)
    ResponseEntity<?> conflict(InvoiceDraftConflict failure) { return error(409,failure.code(),failure.getMessage()); }
    @ExceptionHandler({InvoiceDraftUnavailable.class,DataAccessException.class,TransactionException.class})
    ResponseEntity<?> unavailable() { return error(503,"SERVICE_UNAVAILABLE","Invoice draft creation unavailable"); }
    private ResponseEntity<?> error(int status,String code,String message) {
        return ResponseEntity.status(status).body(Map.of("error",code,"message",message));
    }
}
