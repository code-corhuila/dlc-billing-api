package com.dilucca.billing.api.items;

import com.dilucca.billing.api.pricing.PaginatedMeta;
import java.util.List;

public record BillableItemPage(List<BillableItem> data, PaginatedMeta meta) { }
