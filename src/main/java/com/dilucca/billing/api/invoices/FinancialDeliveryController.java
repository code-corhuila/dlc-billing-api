package com.dilucca.billing.api.invoices;

import com.dilucca.billing.infrastructure.pdf.InvoicePdfRenderer;
import com.dilucca.billing.infrastructure.pdf.InvoicePatientNames;
import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceEventQuery;
import com.dilucca.billing.infrastructure.persistence.JdbcPaymentRecording;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/invoices/{id}")
public class FinancialDeliveryController {
    private final JdbcInvoiceEventQuery events;
    private final InvoicePdfRenderer renderer;
    private final InvoicePatientNames patients;
    public FinancialDeliveryController(JdbcInvoiceEventQuery events,InvoicePdfRenderer renderer,InvoicePatientNames patients) { this.events=events; this.renderer=renderer; this.patients=patients; }
    private UUID uuid(String value) {
        UUID id=UUID.fromString(value); if(!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException(); return id;
    }
    private JdbcInvoiceEventQuery.State state(String id,List<String> events) {
        if(events.size()!=1) throw new IllegalArgumentException();
        return this.events.find(uuid(id),uuid(events.getFirst())).orElseThrow(JdbcPaymentRecording.Missing::new);
    }
    @GetMapping("/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String id,@RequestParam List<String> sourceEventId) {
        var invoice=state(id,sourceEventId).invoice();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").body(renderer.render(invoice,patients.read(invoice.patientId())));
    }
    @GetMapping("/notification-summary")
    public ResponseEntity<?> summary(@PathVariable String id,@RequestParam List<String> sourceEventId) {
        var state=state(id,sourceEventId); var invoice=state.invoice();
        Map<String,Object> value=new LinkedHashMap<>(Map.of("invoiceId",invoice.id(),"invoiceNumber",invoice.invoiceNumber(),
                "issuedAt",invoice.issuedAt(),"concepts",invoice.items().stream().map(item -> item.description()).toList(),
                "currency","COP","total",invoice.total(),"paidTotal",new BigDecimal(invoice.total()).subtract(new BigDecimal(invoice.balance())).setScale(2).toPlainString(),
                "remainingBalance",invoice.balance(),"status",invoice.status()));
        if(state.recentPaymentAmount()!=null) value.put("recentPaymentAmount",state.recentPaymentAmount());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
    @ExceptionHandler({IllegalArgumentException.class,MissingServletRequestParameterException.class})
    ResponseEntity<?> invalid() { return error(400,"VALIDATION_ERROR"); }
    @ExceptionHandler(JdbcPaymentRecording.Missing.class) ResponseEntity<?> missing() { return error(404,"NOT_FOUND"); }
    @ExceptionHandler({DataAccessException.class,InvoicePdfRenderer.Unavailable.class})
    ResponseEntity<?> unavailable() { return error(503,"SERVICE_UNAVAILABLE"); }
    private ResponseEntity<?> error(int status,String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error",code,"message","Financial representation unavailable"));
    }
}
