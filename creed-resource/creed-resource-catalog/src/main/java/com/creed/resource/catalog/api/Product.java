package com.creed.resource.catalog.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Simple in-memory catalog product used by {@link CatalogController} for local testing.
 *
 * @param sku       server-assigned stock-keeping unit (e.g. {@code CAT-1})
 * @param name      product name
 * @param category  product category
 * @param price     unit price
 * @param stock     units currently in stock
 * @param createdAt creation timestamp
 */
public record Product(
        String sku,
        String name,
        String category,
        BigDecimal price,
        int stock,
        Instant createdAt) {
}
