package com.dilucca.billing.api.invoices;

import com.dilucca.billing.infrastructure.pdf.InvoicePdfRenderer;
import com.dilucca.billing.infrastructure.persistence.JdbcInvoiceQueryRepository;
import com.dilucca.billing.infrastructure.persistence.JdbcPaymentRecording;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class InvoicePdfController {
    private final JdbcInvoiceQueryRepository invoices;
    private final InvoicePdfRenderer renderer;
    public InvoicePdfController(JdbcInvoiceQueryRepository invoices,InvoicePdfRenderer renderer) { this.invoices=invoices; this.renderer=renderer; }
    @GetMapping("/api/v1/invoices/{id}/pdf")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        UUID invoice=UUID.fromString(id);
        if(!invoice.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException();
        var value=invoices.find(invoice).orElseThrow(JdbcPaymentRecording.Missing::new);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header("Content-Disposition","attachment; filename=\"invoice-"+invoice+".pdf\"")
                .header("X-Content-Type-Options","nosniff").body(renderer.render(value));
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid() { return error(400,"VALIDATION_ERROR"); }
    @ExceptionHandler(JdbcPaymentRecording.Missing.class) ResponseEntity<?> missing() { return error(404,"NOT_FOUND"); }
    @ExceptionHandler({DataAccessException.class,InvoicePdfRenderer.Unavailable.class})
    ResponseEntity<?> unavailable() { return error(503,"SERVICE_UNAVAILABLE"); }
    private ResponseEntity<?> error(int status,String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error",code,"message","Invoice PDF unavailable"));
    }
}
