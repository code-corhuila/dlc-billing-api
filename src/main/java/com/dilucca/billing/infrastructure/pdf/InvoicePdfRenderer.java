package com.dilucca.billing.infrastructure.pdf;

import com.dilucca.billing.api.invoices.Invoice;
import java.io.*;
import java.math.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;

/** Stateless, on-demand projection: never stores a document or reads clinical data. */
@org.springframework.stereotype.Component
public class InvoicePdfRenderer {
    public byte[] render(Invoice invoice) {
        try(var doc=new PDDocument(); var output=new ByteArrayOutputStream();
                var source=InvoicePdfRenderer.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            if(source==null) throw new IOException();
            var font=PDType0Font.load(doc,source);
            try(var page=new Pages(doc,font)) {
                page.line("Factura: "+(invoice.invoiceNumber()==null ? "BORRADOR - "+invoice.id() : invoice.invoiceNumber()));
                page.line("Paciente: "+invoice.patientId());
                page.line("Estado: "+invoice.status()+" | Versión: "+invoice.version());
                page.line("Emisión: "+(invoice.issuedAt()==null ? "Pendiente" : invoice.issuedAt()));
                page.line("Actualización: "+invoice.updatedAt());
                page.line("");
                int number=0;
                for(var item:invoice.items()) {
                    page.line((++number)+". "+item.description());
                    var total=new BigDecimal(item.quantity()).multiply(new BigDecimal(item.unitPrice())).setScale(2,RoundingMode.HALF_UP);
                    page.line("Cantidad: "+item.quantity()+" | Precio: "+item.unitPrice()+" COP | Importe: "+total.toPlainString()+" COP");
                    page.line("");
                }
                page.reserve(42);
                page.line("Total: "+invoice.total()+" COP");
                page.line("Pagado: "+new BigDecimal(invoice.total()).subtract(new BigDecimal(invoice.balance())).setScale(2).toPlainString()+" COP");
                page.line("Saldo: "+invoice.balance()+" COP");
            }
            doc.save(output); return output.toByteArray();
        } catch(IOException | IllegalArgumentException failure) { throw new Unavailable(); }
    }
    private static final class Pages implements AutoCloseable {
        private final PDDocument document;
        private final PDFont font;
        private PDPageContentStream stream;
        private float y;
        Pages(PDDocument document,PDFont font) throws IOException { this.document=document; this.font=font; next(); }
        private void text(String value,float x,float at,int size) throws IOException {
            stream.beginText(); stream.setFont(font,size); stream.newLineAtOffset(x,at); stream.showText(value); stream.endText();
        }
        private void next() throws IOException {
            if(stream!=null) stream.close();
            var page=new PDPage(PDRectangle.A4); document.addPage(page);
            stream=new PDPageContentStream(document,page);
            stream.setNonStrokingColor(0.05f,0.25f,0.27f);
            text("DI LUCCA",48,790,22); text("Estado financiero de factura",48,769,11);
            text("Página "+document.getNumberOfPages()+" | Comprobante interno",48,32,9);
            stream.setNonStrokingColor(0f,0f,0f); y=736;
        }
        void line(String raw) throws IOException {
            String value=raw.replaceAll("[\\p{Cc}\\p{Cf}]"," ");
            if(value.isEmpty()) { if(y<64) next(); y-=14; return; }
            while(!value.isEmpty()) {
                int end=0,lastSpace=-1;
                for(int at=0;at<value.length();) {
                    int until=at+Character.charCount(value.codePointAt(at));
                    if(font.getStringWidth(value.substring(0,until))/1000*10>495) break;
                    end=until; if(value.charAt(at)==' ') lastSpace=at; at=until;
                }
                if(end==0) throw new IOException();
                if(end<value.length() && lastSpace>0) end=lastSpace;
                if(y<64) next();
                text(value.substring(0,end),48,y,10); y-=14;
                value=value.substring(end).stripLeading();
            }
        }
        void reserve(int height) throws IOException { if(y-height<50) next(); }
        public void close() throws IOException { if(stream!=null) stream.close(); }
    }
    public static class Unavailable extends RuntimeException {
        public Unavailable() { super("Invoice PDF unavailable"); }
    }
}
