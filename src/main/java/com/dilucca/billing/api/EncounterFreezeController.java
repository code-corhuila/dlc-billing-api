package com.dilucca.billing.api;

import com.dilucca.billing.api.pricing.PriceCreationConflict;
import com.dilucca.billing.infrastructure.persistence.JdbcEncounterWriteCoordinator;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
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
@RequestMapping("/internal/v1/encounter-accounts/{record}/{consultation}/manual-charges/freezes")
public class EncounterFreezeController {
    private final JdbcEncounterWriteCoordinator coordinator;
    private final JsonMapper parser=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public EncounterFreezeController(JdbcEncounterWriteCoordinator coordinator) { this.coordinator=coordinator; }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> freeze(@PathVariable String record,@PathVariable String consultation,
            @RequestHeader("Idempotency-Key") List<String> keys,@RequestBody String body,@AuthenticationPrincipal Jwt service) {
        UUID operation;
        try {
            var node=parser.readTree(body);
            if(node==null || !node.isObject() || node.size()!=1 || !node.has("operationId") || !node.get("operationId").isString()) throw new IllegalArgumentException();
            operation=uuid(node.get("operationId").stringValue());
        } catch(Exception invalid) { throw new IllegalArgumentException(); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(coordinator.freezeCommand(key(keys),service.getSubject(),uuid(record),uuid(consultation),operation));
    }
    @PostMapping("/{operation}/releases")
    public ResponseEntity<?> release(@PathVariable String record,@PathVariable String consultation,@PathVariable String operation,
            @RequestHeader("Idempotency-Key") List<String> keys,@AuthenticationPrincipal Jwt service) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(coordinator.releaseCommand(key(keys),service.getSubject(),uuid(record),uuid(consultation),uuid(operation)));
    }
    private UUID key(List<String> values) { if(values.size()!=1) throw new IllegalArgumentException(); return uuid(values.getFirst()); }
    private UUID uuid(String value) { UUID id=UUID.fromString(value); if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); return id; }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MissingRequestHeaderException.class})
    ResponseEntity<?> invalid() { return error(400,"VALIDATION_ERROR"); }
    @ExceptionHandler(JdbcEncounterWriteCoordinator.Conflict.class) ResponseEntity<?> conflict() { return error(409,"ENCOUNTER_CONFLICT"); }
    @ExceptionHandler(PriceCreationConflict.class) ResponseEntity<?> keyConflict(PriceCreationConflict failure) { return error(409,failure.code()); }
    @ExceptionHandler(JdbcEncounterWriteCoordinator.Missing.class) ResponseEntity<?> missing() { return error(404,"NOT_FOUND"); }
    @ExceptionHandler({DataAccessException.class,TransactionException.class}) ResponseEntity<?> unavailable() { return error(503,"SERVICE_UNAVAILABLE"); }
    private ResponseEntity<?> error(int status,String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error",code,"message","Encounter coordination unavailable"));
    }
}
