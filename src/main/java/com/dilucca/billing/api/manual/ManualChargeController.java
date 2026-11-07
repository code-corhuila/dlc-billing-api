package com.dilucca.billing.api.manual;

import com.dilucca.billing.api.pricing.PriceCreationConflict;
import com.dilucca.billing.domain.invoicing.*;
import com.dilucca.billing.infrastructure.persistence.JdbcManualChargeRecording;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/manual-charges")
public class ManualChargeController {
    private final JdbcManualChargeRecording recording;
    public ManualChargeController(JdbcManualChargeRecording recording) { this.recording=recording; }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> record(@RequestHeader("Idempotency-Key") List<String> keys,
            @RequestHeader(value="X-Correlation-Id",required=false) String trace,@RequestBody String body,@AuthenticationPrincipal Jwt jwt) {
        if(keys.size()!=1) throw new IllegalArgumentException();
        UUID key=id(keys.getFirst()),actor;
        String role;
        try {
            actor=id(jwt.getSubject());
            var roles=jwt.getClaimAsStringList("roles");
            role=roles.contains("ADMINISTRATOR") ? "ADMINISTRATOR" : "DENTIST";
        } catch(Exception invalid) { throw new InvalidIdentity(); }
        UUID correlation;
        try { correlation=id(trace); } catch(IllegalArgumentException invalid) { correlation=UUID.randomUUID(); }
        var result=recording.record(key,actor,role,ManualChargeInput.parse(body),correlation);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON)
                .header("Location",result.location()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    private static UUID id(String value) {
        if(value==null) throw new IllegalArgumentException();
        UUID parsed=UUID.fromString(value);
        if(!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return parsed;
    }
    private static class InvalidIdentity extends RuntimeException { }
    @ExceptionHandler(InvalidIdentity.class) ResponseEntity<?> unauthorized() { return error(401,"UNAUTHORIZED","Invalid staff identity"); }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MissingRequestHeaderException.class})
    ResponseEntity<?> invalid() { return error(400,"VALIDATION_ERROR","Invalid manual charge request"); }
    @ExceptionHandler(JdbcManualChargeRecording.Missing.class) ResponseEntity<?> missing() { return error(404,"NOT_FOUND","Manual source not found"); }
    @ExceptionHandler(JdbcManualChargeRecording.Forbidden.class) ResponseEntity<?> forbidden() { return error(403,"FORBIDDEN","Manual charge access denied"); }
    @ExceptionHandler({JdbcManualChargeRecording.Conflict.class,InvoiceDraftConflict.class})
    ResponseEntity<?> conflict() { return error(409,"MANUAL_CHARGE_CONFLICT","Manual source is no longer writable"); }
    @ExceptionHandler(PriceCreationConflict.class) ResponseEntity<?> replayConflict(PriceCreationConflict failure) {
        return error(409,failure.code(),"Manual request conflicts with its stored result");
    }
    @ExceptionHandler({InvoiceDraftUnavailable.class,DataAccessException.class,TransactionException.class})
    ResponseEntity<?> unavailable() { return error(503,"SERVICE_UNAVAILABLE","Manual charge authority or persistence unavailable"); }
    private ResponseEntity<?> error(int code,String error,String message) {
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(Map.of("error",error,"message",message));
    }
}
