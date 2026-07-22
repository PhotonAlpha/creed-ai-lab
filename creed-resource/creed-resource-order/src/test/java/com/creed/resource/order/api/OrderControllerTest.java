package com.creed.resource.order.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@link OrderController}'s checkout endpoint through a standalone MockMvc (no Spring
 * context, no HTTPS listener), same setup as the payment twin's test: the strict-validation
 * contract that distinguishes {@code POST /checkout} from the lenient CRUD create.
 */
class OrderControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Makes @AuthenticationPrincipal Jwt resolve to null (no authentication), matching permitAll.
        mvc = MockMvcBuilders.standaloneSetup(new OrderController())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    void checkoutCreatesNewOrderFromValidPayload() throws Exception {
        mvc.perform(post("/api/order/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer\":\"Ethan\",\"item\":\"Notebook\",\"quantity\":2,\"total\":7.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.customer").value("Ethan"))
                .andExpect(jsonPath("$.total").value(7.00));
    }

    @Test
    void checkoutRejectsBlankOrNonPositiveFieldsUnlikeLenientCreate() throws Exception {
        mvc.perform(post("/api/order/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"Notebook\",\"quantity\":1,\"total\":3.50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/order/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer\":\"Ethan\",\"item\":\"Notebook\",\"quantity\":0,\"total\":3.50}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/order/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer\":\"Ethan\",\"item\":\"Notebook\",\"quantity\":1,\"total\":-1}"))
                .andExpect(status().isBadRequest());

        // the lenient CRUD create still accepts the same sparse payload (backwards compatibility)
        mvc.perform(post("/api/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"Notebook\",\"quantity\":1}"))
                .andExpect(status().isOk());
    }
}
