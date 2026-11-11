package com.dilucca.billing.infrastructure.pdf;

import com.dilucca.billing.api.invoices.Invoice;
import com.dilucca.billing.api.items.BillableItem;
import java.awt.Color;
import java.io.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** Stateless financial projection. Brand resources are bundled, never fetched at render time. */
@org.springframework.stereotype.Component
public class InvoicePdfRenderer {
    private static final Color TEAL = new Color(0x1ea296), TEXT = new Color(0x1f2933),
            MUTED = new Color(0x424752), BORDER = new Color(0xc2c6d4),
            LIGHT = new Color(0xf2f4f6), PALE = new Color(0xedf9f7);

    public byte[] render(Invoice invoice) {
        return render(invoice, "No disponible");
    }

    public byte[] render(Invoice invoice, String patientName) {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var regular = font(document, "Inter-Regular.ttf");
            var bold = font(document, "Inter-SemiBold.ttf");
            try (var source = resource("logo.png")) {
                var logo = PDImageXObject.createFromByteArray(document, source.readAllBytes(), "DI LUCCA");
                document.getDocumentInformation().setTitle("Factura " + number(invoice));
                document.getDocumentInformation().setAuthor("DI LUCCA");
                try (var pages = new Pages(document, regular, bold, logo, invoice, patientName)) {
                    pages.facts(); pages.tableHeader();
                    int index = 0;
                    for (var item : invoice.items()) pages.item(item, ++index);
                    if (invoice.items().isEmpty()) pages.empty();
                    pages.summary();
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException | IllegalArgumentException failure) {
            throw new Unavailable();
        }
    }

    private static InputStream resource(String name) throws IOException {
        var source = InvoicePdfRenderer.class.getResourceAsStream("/billing/pdf/" + name);
        if (source == null) throw new IOException("Missing bundled invoice resource");
        return source;
    }
    private static PDFont font(PDDocument document, String name) throws IOException {
        try (var source = resource(name)) { return PDType0Font.load(document, source); }
    }
    private static String number(Invoice invoice) {
        return invoice.invoiceNumber() == null ? "BORRADOR - " + invoice.id() : invoice.invoiceNumber();
    }
    private static String clean(String value) { return value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").strip(); }

    private static final class Pages implements AutoCloseable {
        private static final float M = 44, RIGHT = PDRectangle.A4.getWidth() - M,
                WIDTH = RIGHT - M, BOTTOM = 72, LEADING = 13;
        private static final float[] COLUMNS = {265, 56, 91, WIDTH - 412};
        private final PDDocument document;
        private final PDFont regular, bold;
        private final PDImageXObject logo;
        private final Invoice invoice;
        private final String patientName;
        private PDPageContentStream stream;
        private float y;

        Pages(PDDocument document, PDFont regular, PDFont bold, PDImageXObject logo, Invoice invoice, String patientName) throws IOException {
            this.document = document; this.regular = regular; this.bold = bold;
            this.logo = logo; this.invoice = invoice; this.patientName = patientName; next();
        }
        private float width(String text, PDFont font, float size) throws IOException {
            return font.getStringWidth(text) / 1000 * size;
        }
        private void text(String value, float x, float at, float size, PDFont font, Color color) throws IOException {
            stream.setNonStrokingColor(color);
            stream.beginText(); stream.setFont(font, size); stream.newLineAtOffset(x, at);
            stream.showText(clean(value)); stream.endText();
        }
        private void right(String value, float edge, float at, float size, PDFont font, Color color) throws IOException {
            text(value, edge - width(clean(value), font, size), at, size, font, color);
        }
        private void box(float x, float bottom, float w, float h, Color fill) throws IOException {
            stream.setNonStrokingColor(fill); stream.addRect(x, bottom, w, h); stream.fill();
        }
        private void line(float x, float at, float edge) throws IOException {
            stream.setStrokingColor(BORDER); stream.setLineWidth(.5f);
            stream.moveTo(x, at); stream.lineTo(edge, at); stream.stroke();
        }
        private List<String> wrap(String raw, PDFont font, float size, float max) throws IOException {
            String remaining = clean(raw);
            var lines = new ArrayList<String>();
            while (!remaining.isEmpty()) {
                int end = 0, space = -1;
                for (int at = 0; at < remaining.length();) {
                    int until = at + Character.charCount(remaining.codePointAt(at));
                    if (width(remaining.substring(0, until), font, size) > max) break;
                    end = until; if (remaining.charAt(at) == ' ') space = at; at = until;
                }
                if (end == 0) throw new IOException("Invoice column too narrow");
                if (end < remaining.length() && space > 0) end = space;
                lines.add(remaining.substring(0, end)); remaining = remaining.substring(end).stripLeading();
            }
            return lines.isEmpty() ? List.of("") : lines;
        }
        private String status() {
            return switch (invoice.status()) {
                case "DRAFT" -> "Borrador";
                case "ISSUED" -> "Emitida";
                case "PARTIALLY_PAID" -> "Pago parcial";
                case "PAID" -> "Pagada";
                case "CANCELLED" -> "Cancelada";
                default -> invoice.status();
            };
        }
        private void next() throws IOException {
            if (stream != null) stream.close();
            var page = new PDPage(PDRectangle.A4); document.addPage(page);
            stream = new PDPageContentStream(document, page);
            float top = PDRectangle.A4.getHeight();
            box(32, top - 146, PDRectangle.A4.getWidth() - 64, 114, LIGHT);
            box(32, top - 36, PDRectangle.A4.getWidth() - 64, 4, TEAL);
            stream.drawImage(logo, M, top - 128, 100, 81);
            text("FACTURA", 164, top - 62, 12, bold, TEAL);
            float at = top - 88;
            for (var value : wrap(number(invoice), bold, 13, RIGHT - 164)) {
                text(value, 164, at, 13, bold, TEXT); at -= 17;
            }
            right(status(), RIGHT - 12, top - 61, 10, bold, MUTED);
            y = Math.min(top - 168, at - 24);
        }
        void facts() throws IOException {
            box(M, y - 132, WIDTH, 132, Color.WHITE);
            line(M, y, RIGHT); line(M, y - 132, RIGHT);
            text("DATOS DE LA FACTURA", M + 12, y - 19, 9, bold, TEAL);
            fact("Paciente", patientName, M + 12, y - 39);
            fact("Emisión", invoice.issuedAt() == null ? "Pendiente" : date(invoice.issuedAt()), M + WIDTH / 2 + 8, y - 39);
            fact("Identificador de factura", invoice.id().toString(), M + 12, y - 70);
            fact("Actualización", date(invoice.updatedAt()), M + WIDTH / 2 + 8, y - 70);
            fact("Versión", Integer.toString(invoice.version()), M + WIDTH / 2 + 8, y - 101);
            y -= 159; text("Conceptos facturados", M, y, 13, bold, TEXT); y -= 17;
        }
        private void fact(String label, String value, float x, float at) throws IOException {
            text(label, x, at, 8, bold, MUTED);
            for (var part : wrap(value, regular, 8.5f, WIDTH / 2 - 26)) {
                text(part, x, at - 13, 8.5f, regular, TEXT); at -= 11;
            }
        }
        void tableHeader() throws IOException {
            box(M, y - 32, WIDTH, 32, LIGHT);
            String[] labels = {"Descripción del servicio", "Cantidad", "Precio (COP)", "Importe (COP)"};
            float x = M;
            for (int column = 0; column < COLUMNS.length; column++) {
                if (column == 0) text(labels[column], x + 10, y - 20, 8.5f, bold, MUTED);
                else right(labels[column], x + COLUMNS[column] - 10, y - 20, 8, bold, MUTED);
                x += COLUMNS[column];
            }
            y -= 32; line(M, y, RIGHT);
        }
        void item(BillableItem item, int number) throws IOException {
            var amount = new BigDecimal(item.quantity()).multiply(new BigDecimal(item.unitPrice()))
                    .setScale(2, RoundingMode.HALF_UP).toPlainString();
            String[] values = {number + ". " + item.description(), item.quantity(), item.unitPrice() + " COP", amount + " COP"};
            var cells = new ArrayList<List<String>>();
            for (int column = 0; column < values.length; column++)
                cells.add(wrap(values[column], regular, 9, COLUMNS[column] - 20));
            int count = cells.stream().mapToInt(List::size).max().orElse(1), offset = 0;
            if (count * LEADING + 20 <= 500 && y - count * LEADING - 20 < BOTTOM) {
                next(); tableHeader();
            }
            while (offset < count) {
                int available = (int) ((y - BOTTOM - 20) / LEADING);
                if (available < 1) { next(); tableHeader(); continue; }
                int chunk = Math.min(count - offset, available);
                float height = chunk * LEADING + 20, x = M;
                box(M, y - height, WIDTH, height, number % 2 == 0 ? new Color(0xf7f9fb) : Color.WHITE);
                for (int column = 0; column < cells.size(); column++) {
                    var cell = cells.get(column);
                    for (int row = 0; row < chunk && offset + row < cell.size(); row++) {
                        var value = cell.get(offset + row); float at = y - 16 - row * LEADING;
                        if (column == 0) text(value, x + 10, at, 9, regular, TEXT);
                        else right(value, x + COLUMNS[column] - 10, at, 9, regular, TEXT);
                    }
                    x += COLUMNS[column];
                }
                y -= height; line(M, y, RIGHT); offset += chunk;
            }
        }
        void empty() throws IOException { text("Sin conceptos registrados.", M + 10, y - 24, 10, regular, MUTED); y -= 48; }
        void summary() throws IOException {
            if (y - 146 < BOTTOM) next();
            y -= 26; box(RIGHT - 258, y - 106, 258, 106, PALE); box(RIGHT - 258, y - 3, 258, 3, TEAL);
            var paid = new BigDecimal(invoice.total()).subtract(new BigDecimal(invoice.balance())).setScale(2).toPlainString();
            right("Total: " + invoice.total() + " COP", RIGHT - 16, y - 32, 14, bold, TEXT);
            right("Pagado: " + paid + " COP", RIGHT - 16, y - 60, 11, regular, MUTED);
            right("Saldo: " + invoice.balance() + " COP", RIGHT - 16, y - 88, 14, bold, TEAL);
        }
        public void close() throws IOException {
            if (stream != null) stream.close();
            int count = document.getNumberOfPages();
            for (int index = 0; index < count; index++) {
                stream = new PDPageContentStream(document, document.getPage(index), PDPageContentStream.AppendMode.APPEND, true);
                line(M, 52, RIGHT);
                text("Comprobante interno · Valores en COP", M, 36, 8, regular, MUTED);
                right("Página " + (index + 1) + " de " + count, RIGHT, 36, 8, regular, MUTED);
                text("Factura (ID): " + invoice.id(), M, 22, 7, regular, MUTED); stream.close();
            }
        }
    }
    public static class Unavailable extends RuntimeException {
        public Unavailable() { super("Invoice PDF unavailable"); }
    }

    private static String date(Instant value) {
        return DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withZone(ZoneId.of("America/Bogota")).format(value);
    }
}
