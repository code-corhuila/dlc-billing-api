package com.dilucca.billing.api.invoices;

import com.dilucca.billing.infrastructure.persistence.JdbcPaymentRecording;
import com.dilucca.billing.infrastructure.persistence.JdbcPaymentQueryRepository;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/invoices/{id}/payments")
public class PaymentController {
    private final JdbcPaymentRecording recording;
    private final JdbcPaymentQueryRepository query;
    private final JsonMapper parser=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public PaymentController(JdbcPaymentRecording recording,JdbcPaymentQueryRepository query) {
        this.recording=recording; this.query=query;
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> record(@PathVariable String id,@RequestHeader("Idempotency-Key") List<String> keys,
            @RequestHeader(value="X-Correlation-Id",required=false) String trace,@RequestBody String body,@AuthenticationPrincipal Jwt jwt) {
        UUID invoice=identifier(id);
        if (keys.size()!=1) throw new IllegalArgumentException();
        UUID key=identifier(keys.getFirst());
        var value=payload(body);
        UUID actor;
        try { actor=identifier(jwt==null ? null : jwt.getSubject()); }
        catch(IllegalArgumentException invalid) { throw new InvalidIdentity(); }
        UUID correlation;
        try { correlation=identifier(trace); } catch(IllegalArgumentException invalid) { correlation=UUID.randomUUID(); }
        var result=recording.record(key,actor,invoice,value.amount(),value.method(),value.version(),correlation);
        return ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON)
                .header("Location",result.location()).cacheControl(CacheControl.noStore()).body(result.body());
    }
    @GetMapping
    public Object list(@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int limit) {
        UUID invoice=identifier(id);
        if(page<1 || limit<1 || limit>100 || (long)(page-1)*limit>Integer.MAX_VALUE) throw new IllegalArgumentException();
        return query.list(invoice,page,limit);
    }
    private Input payload(String body) {
        try {
            var n=parser.readTree(body);
            if(n==null || !n.isObject() || n.size()!=3 || !n.has("amount") || !n.has("method") || !n.has("expectedVersion")
                    || !n.get("amount").isString() || !n.get("amount").stringValue().matches("(?:0|[1-9][0-9]{0,11})\\.[0-9]{2}")
                    || !n.get("method").isString() || n.get("method").stringValue().isBlank() || n.get("method").stringValue().length()>40
                    || !n.get("expectedVersion").isIntegralNumber() || !n.get("expectedVersion").canConvertToLong()
                    || n.get("expectedVersion").longValue()<1) throw new IllegalArgumentException();
            var amount=new BigDecimal(n.get("amount").stringValue());
            if(amount.signum()<=0) throw new IllegalArgumentException();
            return new Input(amount,n.get("method").stringValue(),n.get("expectedVersion").longValue());
        } catch(Exception invalid) { throw new IllegalArgumentException(); }
    }
    private UUID identifier(String value) {
        if(value==null) throw new IllegalArgumentException();
        UUID id=UUID.fromString(value);
        if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return id;
    }
    private record Input(BigDecimal amount,String method,long version) { }
    private static class InvalidIdentity extends RuntimeException { }
    @ExceptionHandler(InvalidIdentity.class) ResponseEntity<?> unauthorized() { return error(401,"UNAUTHORIZED","Invalid staff identity"); }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,MissingRequestHeaderException.class,MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid() { return error(400,"VALIDATION_ERROR","Invalid payment request"); }
    @ExceptionHandler(JdbcPaymentRecording.Missing.class) ResponseEntity<?> missing() { return error(404,"NOT_FOUND","Invoice not found"); }
    @ExceptionHandler(JdbcPaymentRecording.Conflict.class)
    ResponseEntity<?> conflict(JdbcPaymentRecording.Conflict failure) { return error(409,failure.code(),failure.getMessage()); }
    @ExceptionHandler({DataAccessException.class,TransactionException.class})
    ResponseEntity<?> unavailable() { return error(503,"SERVICE_UNAVAILABLE","Payment persistence unavailable"); }
    private ResponseEntity<?> error(int code,String error,String message) {
        return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).body(Map.of("error",error,"message",message));
    }
}
