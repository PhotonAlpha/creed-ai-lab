package com.creed.resource.payment.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@link PaymentController} through a standalone MockMvc (no Spring context, no HTTPS
 * listener): CRUD round-trips, the query filters, and the payment lifecycle state machine
 * (authorize/capture/refund transitions, cancel guard, 404/400/409 contracts).
 */
class PaymentControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // The resolver makes @AuthenticationPrincipal Jwt resolve to null (no authentication),
        // matching the local permitAll behaviour; without it standalone MVC would try to
        // instantiate Jwt as a model attribute.
        mvc = MockMvcBuilders.standaloneSetup(new PaymentController())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    /** Creates a PENDING payment and returns its id. */
    private String createPayment(String orderId) throws Exception {
        String location = mvc.perform(post("/api/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"customer\":\"Carol\","
                                + "\"amount\":19.90,\"currency\":\"SGD\",\"method\":\"card\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.method").value("CARD"))
                .andReturn().getResponse().getContentAsString();
        return com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(location).get("id").asText();
    }

    @Test
    void pingReportsServiceUpWithAnonymousSubject() throws Exception {
        mvc.perform(get("/api/payment/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("creed-resource-payment"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.subject").value("anonymous"));
    }

    @Test
    void listReturnsSeededPaymentsAndFiltersByOrderIdAndStatus() throws Exception {
        mvc.perform(get("/api/payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))));

        mvc.perform(get("/api/payment").param("orderId", "ORD-900"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("CAPTURED"));

        mvc.perform(get("/api/payment").param("status", "pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderId").value("ORD-901"));
    }

    @Test
    void getReturnsPaymentOr404() throws Exception {
        String id = createPayment("ORD-1");
        mvc.perform(get("/api/payment/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ORD-1"));
        mvc.perform(get("/api/payment/PAY-NOPE"))
                .andExpect(status().isNotFound());
    }

    @Test
    void checkoutCreatesPaymentAlreadyAuthorized() throws Exception {
        mvc.perform(post("/api/payment/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-42\",\"customer\":\"Dave\",\"amount\":7.00,"
                                + "\"currency\":\"SGD\",\"method\":\"wallet\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.method").value("WALLET"))
                .andExpect(jsonPath("$.orderId").value("ORD-42"));
    }

    @Test
    void checkoutValidatesLikeCreateAndFeedsTheNormalLifecycle() throws Exception {
        mvc.perform(post("/api/payment/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-42\",\"amount\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/payment/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-42\",\"amount\":5,\"method\":\"IOU\"}"))
                .andExpect(status().isBadRequest());

        // AUTHORIZED from checkout is a first-class lifecycle state: capture works, re-authorize 409s
        String body = mvc.perform(post("/api/payment/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-43\",\"customer\":\"Eve\",\"amount\":3.30}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(body).get("id").asText();
        mvc.perform(post("/api/payment/" + id + "/authorize"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/payment/" + id + "/capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    void createRejectsMissingAmountAndUnknownMethod() throws Exception {
        mvc.perform(post("/api/payment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/payment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-1\",\"amount\":-5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/payment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"ORD-1\",\"amount\":5,\"method\":\"CASH\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateMergesOnlyProvidedFields() throws Exception {
        String id = createPayment("ORD-2");
        mvc.perform(put("/api/payment/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customer\":\"Dave\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer").value("Dave"))
                .andExpect(jsonPath("$.orderId").value("ORD-2"))
                .andExpect(jsonPath("$.amount").value(19.90));
        mvc.perform(put("/api/payment/PAY-NOPE").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void lifecycleAuthorizeCaptureRefundHappyPath() throws Exception {
        String id = createPayment("ORD-3");
        mvc.perform(post("/api/payment/" + id + "/authorize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));
        mvc.perform(post("/api/payment/" + id + "/capture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        mvc.perform(post("/api/payment/" + id + "/refund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void transitionsRejectWrongPredecessorStateWith409() throws Exception {
        String id = createPayment("ORD-4");
        // PENDING: capture and refund both need a later state.
        mvc.perform(post("/api/payment/" + id + "/capture"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/payment/" + id + "/refund"))
                .andExpect(status().isConflict());
        // Unknown id is 404, not 409.
        mvc.perform(post("/api/payment/PAY-NOPE/authorize"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelAllowedBeforeCaptureRejectedAfter() throws Exception {
        String id = createPayment("ORD-5");
        mvc.perform(delete("/api/payment/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        String captured = createPayment("ORD-6");
        mvc.perform(post("/api/payment/" + captured + "/authorize")).andExpect(status().isOk());
        mvc.perform(post("/api/payment/" + captured + "/capture")).andExpect(status().isOk());
        mvc.perform(delete("/api/payment/" + captured))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/payment/PAY-NOPE"))
                .andExpect(status().isNotFound());
    }
}
