package com.dilucca.billing.api.invoices;

import com.dilucca.billing.api.pricing.PaginatedMeta;
import java.util.List;
public record InvoicePage(List<Invoice> data, PaginatedMeta meta) { }
