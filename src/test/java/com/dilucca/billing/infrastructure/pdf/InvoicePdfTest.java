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
    @Test void printsPatientAndMinutePrecisionWithoutAppointmentId() throws Exception {
        var time=Instant.parse("2026-11-08T15:30:27Z");
        var invoice=new Invoice(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"BIL-1",time,List.of(),
                "10.00","0.00","COP","PAID",time,time,1);
        try(var doc=Loader.loadPDF(new InvoicePdfRenderer().render(invoice, "Ana García"))) {
            var text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Ana García"));
            assertTrue(text.contains(invoice.id().toString()));
            assertFalse(text.contains(invoice.appointmentId().toString()));
            assertFalse(text.contains("Cita (ID)"));
            assertTrue(text.contains("2026-11-08 10:30"));
            assertFalse(text.contains("10:30:27"));
        }
    }
    @Test void rendersBrandedSinglePageAndExactSummaryWithoutInventedPatientDetails() throws Exception {
        var time=Instant.parse("2026-11-08T15:30:00Z");
        var patient=UUID.fromString("10000000-0000-4000-8000-000000000001");
        var items=new ArrayList<BillableItem>();
        String[] names={"Consulta odontológica", "Material autorizado", "Restauración dental"};
        String[] quantities={"1.0000", "2.0000", "1.0000"};
        String[] prices={"150000.00", "25000.00", "90000.00"};
        for(int i=0;i<names.length;i++) items.add(new BillableItem(UUID.randomUUID(),patient,UUID.randomUUID(),
                names[i],quantities[i],prices[i],"COP",false,time,time,1,"BILLED"));
        var invoice=new Invoice(UUID.fromString("20000000-0000-4000-8000-000000000001"),patient,
                UUID.fromString("30000000-0000-4000-8000-000000000001"),"BIL-EJEMPLO-001",time,items,
                "290000.00","145000.00","COP","PARTIALLY_PAID",time,time,3);
        var bytes=new InvoicePdfRenderer().render(invoice);
        try(var doc=Loader.loadPDF(bytes)) {
            assertEquals(1,doc.getNumberOfPages());
            var text=new PDFTextStripper().getText(doc);
            for(var expected:List.of("Consulta odontológica","Restauración dental","Pago parcial",
                    "Total: 290000.00 COP","Pagado: 145000.00 COP","Saldo: 145000.00 COP","Página 1 de 1"))
                assertTrue(text.contains(expected),expected);
            var resources=doc.getPage(0).getResources();
            boolean image=false;
            for(var name:resources.getXObjectNames()) image|=resources.isImageXObject(name);
            assertTrue(image,"The shared DI LUCCA logo must be embedded");
            for(var name:resources.getFontNames()) assertTrue(resources.getFont(name).isEmbedded());
            assertFalse(text.contains("IVA"));
            assertFalse(text.contains("contacto@"));
        }
        var evidence=Path.of("target/pdf-evidence");Files.createDirectories(evidence);
        Files.write(evidence.resolve("di-lucca-invoice.pdf"),bytes);
    }

    @Test void wrapsUnbrokenDescriptionsAcrossPagesAndKeepsAllTextInsideMargins() throws Exception {
        var time=Instant.parse("2026-11-08T15:30:00Z");
        var patient=UUID.randomUUID();
        var item=new BillableItem(UUID.randomUUID(),patient,UUID.randomUUID(),
                "Inicio\n"+"Restauración".repeat(400)+" FIN-COMPLETO","1.0000","10.01","COP",false,time,time,1,"BILLED");
        var invoice=new Invoice(UUID.randomUUID(),patient,null,"BIL-"+"N".repeat(76),time,List.of(item),
                "10.01","0.00","COP","PAID",time,time,4);
        try(var doc=Loader.loadPDF(new InvoicePdfRenderer().render(invoice))) {
            assertTrue(doc.getNumberOfPages()>1);
            var text=new PDFTextStripper().getText(doc);
            assertTrue(text.contains("FIN-COMPLETO"));
            assertTrue(text.contains("Saldo: 0.00 COP"));
            var positions=new PDFTextStripper() {
                @Override protected void processTextPosition(org.apache.pdfbox.text.TextPosition position) {
                    assertTrue(position.getXDirAdj()>=31, "Text crossed left margin");
                    assertTrue(position.getXDirAdj()+position.getWidthDirAdj()<=564, "Text crossed right margin");
                    assertTrue(position.getYDirAdj()>=30 && position.getYDirAdj()<=823, "Text crossed page margin");
                    super.processTextPosition(position);
                }
            };
            positions.getText(doc);
            for(int page=1;page<=doc.getNumberOfPages();page++) {
                var one=new PDFTextStripper();one.setStartPage(page);one.setEndPage(page);
                assertTrue(one.getText(doc).contains("Página "+page+" de "+doc.getNumberOfPages()));
            }
        }
    }

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
            var last=new PDFTextStripper(); last.setStartPage(doc.getNumberOfPages());
            assertTrue(last.getText(doc).contains("Total: 0.35 COP"));
            assertFalse(text.contains("justification"));
            var evidence=Path.of("target/pdf-evidence"); Files.createDirectories(evidence);
            Files.write(evidence.resolve("synthetic-invoice.pdf"),bytes);
            var renderer=new PDFRenderer(doc);
            for(int page=0;page<doc.getNumberOfPages();page++)
                ImageIO.write(renderer.renderImageWithDPI(page,100),"png",evidence.resolve("page-"+page+".png").toFile());
        }
    }
}
