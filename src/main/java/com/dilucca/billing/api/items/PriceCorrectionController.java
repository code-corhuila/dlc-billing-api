package com.dilucca.billing.api.items;

import com.dilucca.billing.infrastructure.persistence.JdbcPriceCorrectionRecording;
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
public class PriceCorrectionController {
    private final JdbcPriceCorrectionRecording recording;
    public PriceCorrectionController(JdbcPriceCorrectionRecording recording) { this.recording=recording; }

    @PostMapping(value="/api/v1/billable-items/{id}/approvals",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> correct(@PathVariable String id,
            @RequestHeader("Idempotency-Key") List<String> keys,@RequestBody String body,@AuthenticationPrincipal Jwt jwt) {
        UUID item=identifier(id);
        if(keys.size()!=1) throw new IllegalArgumentException();
        UUID key=identifier(keys.getFirst()); var input=PriceCorrectionInput.parse(body);
        UUID actor;
        try { actor=identifier(jwt==null?null:jwt.getSubject()); }
        catch(IllegalArgumentException invalid) { throw new InvalidIdentity(); }
        var result=recording.record(key,actor,item,input);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(result.body());
    }
    private UUID identifier(String value) {
        if(value==null) throw new IllegalArgumentException();
        UUID id=UUID.fromString(value);
        if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return id;
    }
    private static class InvalidIdentity extends RuntimeException { }
    @ExceptionHandler(InvalidIdentity.class)
    ResponseEntity<?> unauthorized() { return error(401,"UNAUTHORIZED","Invalid staff identity"); }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MissingRequestHeaderException.class})
    ResponseEntity<?> invalid() { return error(400,"VALIDATION_ERROR","Invalid price correction request"); }
    @ExceptionHandler(JdbcPriceCorrectionRecording.Missing.class)
    ResponseEntity<?> missing() { return error(404,"NOT_FOUND","Billable item not found"); }
    @ExceptionHandler(JdbcPriceCorrectionRecording.Conflict.class)
    ResponseEntity<?> conflict(JdbcPriceCorrectionRecording.Conflict failure) { return error(409,failure.code(),failure.getMessage()); }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    ResponseEntity<?> unavailable() { return error(503,"SERVICE_UNAVAILABLE","Price correction persistence unavailable"); }
    private ResponseEntity<?> error(int status,String code,String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error",code,"message",message));
    }
}
