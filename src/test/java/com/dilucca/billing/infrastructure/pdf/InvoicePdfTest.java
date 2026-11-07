package com.dilucca.billing.infrastructure.pdf;

import static org.junit.jupiter.api.Assertions.*;
import com.dilucca.billing.api.invoices.Invoice;
import com.dilucca.billing.api.items.BillableItem;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class InvoicePdfTest {
    @Test void embedsExactFinancialFactsAccentsAndPaginatedLongConceptsWithoutNarrative() throws Exception {
        UUID patient=UUID.randomUUID(),procedure=UUID.randomUUID();
        var time=Instant.parse("2026-11-06T15:00:00Z");
        var items=new ArrayList<BillableItem>();
        for(int n=0;n<35;n++) items.add(new BillableItem(UUID.randomUUID(),patient,procedure,
                "Concepto autorizado número "+n+" - Restauración y evaluación "+"material ".repeat(12),
                "0.5000","0.01","COP",false,time,time,2,"BILLED"));
        var invoice=new Invoice(UUID.randomUUID(),patient,null,"BIL-SYNTHETIC-PDF",time,items,
                "0.35","0.20","COP","PARTIALLY_PAID",time,time,3);
        byte[] bytes=new InvoicePdfRenderer().render(invoice);
        try(var doc=Loader.loadPDF(bytes)) {
            assertTrue(doc.getNumberOfPages()>1);
            String text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Restauración"));
            assertTrue(text.contains("número 34"));
            assertTrue(text.contains("0.01 COP")); // separately rounded line, never float arithmetic
            assertTrue(text.contains("Pagado: 0.15 COP"));
            assertTrue(text.contains("Saldo: 0.20 COP"));
            assertFalse(text.contains("justification"));
            var evidence=Path.of("target/pdf-evidence"); Files.createDirectories(evidence);
            Files.write(evidence.resolve("synthetic-invoice.pdf"),bytes);
            var renderer=new PDFRenderer(doc);
            for(int page=0;page<doc.getNumberOfPages();page++)
                ImageIO.write(renderer.renderImageWithDPI(page,100),"png",evidence.resolve("page-"+page+".png").toFile());
        }
    }
}
