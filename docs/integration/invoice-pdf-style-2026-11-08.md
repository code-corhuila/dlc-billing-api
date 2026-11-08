# DI LUCCA invoice PDF presentation

The shared invoice renderer now follows the supplied DI LUCCA visual reference and
the existing portal: the unchanged brand logo, embedded Inter regular/semibold,
teal accents, invoice metadata, aligned item columns, alternating row backgrounds
and a total/paid/balance summary. The same renderer serves current and event-state PDFs.

This changes presentation only. Authorized financial projections, endpoints, headers,
authentication, stored invoice/payment facts, decimal line rounding and historical
snapshot selection remain unchanged. Patient/appointment IDs remain IDs; no unavailable
names, addresses, taxes, discounts, issuer credentials or clinical narrative are invented.

Long descriptions wrap by measured font width, including unbroken words and rows longer
than a page. Continuation pages repeat the brand and column headers. Financial summaries
stay together, and every page includes invoice identity and page X of Y. Fonts, font
license and logo are bundled in the JAR; the renderer makes no external asset requests.

Validation: seven focused renderer/current-PDF/event-delivery tests passed without skips.
The full Maven suite reported 229 tests, zero failures/errors and 83 infrastructure skips.
The short example and all six pages of the long-concept regression were rendered and
visually checked. Tests also verify embedded logo/fonts, exact cents, complete final
concepts, pagination and text margins. The JAR and Docker image built successfully.

The local Billing image is `dlc-billing-api:pdf-style-20261108`. A sample PDF generated
from in-memory example facts is available locally; no sample records are inserted into
the user's cleaned database. Before/after financial workflow acceptance remains in the
earlier reports; this change does not claim a new production deployment or a full
cross-owner acceptance run.
