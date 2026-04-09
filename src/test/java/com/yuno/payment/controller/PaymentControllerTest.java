package com.yuno.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuno.payment.config.EmbeddedRedisConfig;
import com.yuno.payment.dto.CreatePaymentRequest;
import com.yuno.payment.dto.PaymentResponse;
import com.yuno.payment.model.PaymentMethod;
import com.yuno.payment.model.PaymentStatus;
import com.yuno.payment.service.PaymentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller layer tests using MockMvc (no real HTTP server).
 * Tests cover request validation, response codes, and serialization.
 *
 * Test classification: INTEGRATION (controller slice)
 */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private PaymentService paymentService;

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CreatePaymentRequest validRequest() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setIdempotencyKey("idem-ctrl-001");
        req.setMerchantId("merchant-001");
        req.setAmount(new BigDecimal("250.00"));
        req.setCurrency("USD");
        req.setPaymentMethod(PaymentMethod.CARD);
        return req;
    }

    private PaymentResponse mockResponse(UUID id, PaymentStatus status) {
        return PaymentResponse.builder()
                .id(id)
                .idempotencyKey("idem-ctrl-001")
                .merchantId("merchant-001")
                .amount(new BigDecimal("250.00"))
                .currency("USD")
                .paymentMethod(PaymentMethod.CARD)
                .status(status)
                .providerName("ProviderA")
                .providerReference("PA-TEST001")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // -----------------------------------------------------------------------
    // INTEGRATION — POST /api/v1/payments
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[INTEGRATION] POST valid CARD payment → 201 Created")
    void postValidCardPayment_Returns201() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.createPayment(any())).thenReturn(mockResponse(id, PaymentStatus.SUCCESS));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.providerName").value("ProviderA"));
    }

    @Test
    @DisplayName("[INTEGRATION] POST missing idempotencyKey → 400 Bad Request with field error")
    void postMissingIdempotencyKey_Returns400() throws Exception {
        CreatePaymentRequest req = validRequest();
        req.setIdempotencyKey(null);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey").isNotEmpty());
    }

    @Test
    @DisplayName("[INTEGRATION] POST missing merchantId → 400 Bad Request")
    void postMissingMerchantId_Returns400() throws Exception {
        CreatePaymentRequest req = validRequest();
        req.setMerchantId(null);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.merchantId").isNotEmpty());
    }

    @Test
    @DisplayName("[INTEGRATION] POST amount = 0 → 400 Bad Request")
    void postZeroAmount_Returns400() throws Exception {
        CreatePaymentRequest req = validRequest();
        req.setAmount(BigDecimal.ZERO);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").isNotEmpty());
    }

    @Test
    @DisplayName("[INTEGRATION] POST negative amount → 400 Bad Request")
    void postNegativeAmount_Returns400() throws Exception {
        CreatePaymentRequest req = validRequest();
        req.setAmount(new BigDecimal("-50.00"));

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("[INTEGRATION] POST invalid currency code → 400 Bad Request")
    void postInvalidCurrency_Returns400() throws Exception {
        CreatePaymentRequest req = validRequest();
        req.setCurrency("DOLLAR"); // must be 3-letter ISO code

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.currency").isNotEmpty());
    }

    @Test
    @DisplayName("[INTEGRATION] POST null paymentMethod → 400 Bad Request")
    void postNullPaymentMethod_Returns400() throws Exception {
        CreatePaymentRequest req = validRequest();
        req.setPaymentMethod(null);

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.paymentMethod").isNotEmpty());
    }

    @Test
    @DisplayName("[INTEGRATION] POST empty body → 400 Bad Request")
    void postEmptyBody_Returns400() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // INTEGRATION — GET /api/v1/payments/{id}
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[INTEGRATION] GET existing payment by ID → 200 OK")
    void getPaymentById_Returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(paymentService.getPayment(id)).thenReturn(mockResponse(id, PaymentStatus.SUCCESS));

        mockMvc.perform(get("/api/v1/payments/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("[INTEGRATION] GET payment with invalid UUID format → 400 Bad Request")
    void getPaymentInvalidUuid_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/payments/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // INTEGRATION — GET /api/v1/payments?merchantId=
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[INTEGRATION] GET payments by merchantId → 200 OK with list")
    void getPaymentsByMerchant_Returns200() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(paymentService.getPaymentsByMerchant("merchant-001"))
                .thenReturn(List.of(
                        mockResponse(id1, PaymentStatus.SUCCESS),
                        mockResponse(id2, PaymentStatus.FAILED)));

        mockMvc.perform(get("/api/v1/payments").param("merchantId", "merchant-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("[INTEGRATION] GET payments with no merchantId param → 400 Bad Request")
    void getPaymentsMissingMerchantId_Returns400() throws Exception {
        mockMvc.perform(get("/api/v1/payments"))
                .andExpect(status().isBadRequest());
    }
}
