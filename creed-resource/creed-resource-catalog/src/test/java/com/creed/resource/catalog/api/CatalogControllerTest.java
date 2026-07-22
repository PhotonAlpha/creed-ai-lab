package com.creed.resource.catalog.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@link CatalogController}'s reserve endpoint through a standalone MockMvc (no Spring
 * context, no HTTPS listener), same setup as the payment twin's test: the checkout chain's
 * stock-decrement contract — quote shape, atomic decrement, and the 400/404/409 error paths.
 */
class CatalogControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Makes @AuthenticationPrincipal Jwt resolve to null (no authentication), matching permitAll.
        mvc = MockMvcBuilders.standaloneSetup(new CatalogController())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    void reserveQuotesAndDecrementsStock() throws Exception {
        // seeded: CAT-1 Notebook, price 3.50, stock 100
        mvc.perform(post("/api/catalog/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"CAT-1\",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("CAT-1"))
                .andExpect(jsonPath("$.name").value("Notebook"))
                .andExpect(jsonPath("$.total").value(7.00))
                .andExpect(jsonPath("$.remainingStock").value(98));

        mvc.perform(get("/api/catalog/CAT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(98));
    }

    @Test
    void reserveRejectsBadRequestUnknownSkuAndInsufficientStock() throws Exception {
        mvc.perform(post("/api/catalog/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"\",\"quantity\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/catalog/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"CAT-1\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/catalog/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"CAT-NOPE\",\"quantity\":1}"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/catalog/reserve").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"CAT-1\",\"quantity\":100000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("insufficient stock"))
                .andExpect(jsonPath("$.available").value(100));

        // the rejected reservation must not have touched the stock
        mvc.perform(get("/api/catalog/CAT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(100));
    }
}
