# Current invoice PDF

GET /api/v1/invoices/{id}/pdf renders the current minimized invoice projection
on demand with Apache PDFBox 3.0.8. It embeds the library's Liberation Sans font,
wraps concepts, paginates and keeps totals together. Exact COP line rounding and
paid/balance values follow the owner calculation rule. No clinical source
narrative, justification, persistent document or public URL is introduced.

Administrator/Assistant require JWT plus live IAM validation. Dentist stays denied
until explicit financial permission/current assignment are available. Download
headers use a UUID-only filename, no-store and nosniff. Missing is 404, malformed
UUID is 400; persistence/render failure returns a safe 503.

RED: renderer missing. GREEN: two tests verify protected HTTP and parse/render a
35-item four-page synthetic PDF, accents and exact amounts. All four page PNGs
were visually inspected; the total/paid/balance block was kept on one page after
inspection. Samples remain ignored test evidence, not stored financial records.
Private event-state delivery is a separate increment.

Sources: [PDFBox release](https://pdfbox.apache.org/download.cgi) and
[bundled font](https://github.com/apache/pdfbox/blob/3.0.8/pdfbox/src/main/java/org/apache/pdfbox/pdmodel/font/FontMapperImpl.java).
