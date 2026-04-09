package com.yuno.payment.controller;

import com.yuno.payment.dto.CreatePaymentRequest;
import com.yuno.payment.dto.PaymentResponse;
import com.yuno.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing the Payment Orchestration API.
 *
 * All endpoints are versioned under /api/v1/payments.
 *
 * Endpoints:
 *   POST   /api/v1/payments          - Create and process a new payment
 *   GET    /api/v1/payments/{id}     - Fetch payment by UUID
 *   GET    /api/v1/payments?merchantId={id} - List payments for a merchant
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Create a new payment.
     *
     * The {@code Idempotency-Key} header is an optional alternative to embedding
     * the key in the request body. If both are provided, the body value takes precedence.
     *
     * Returns 201 Created on success, or 200 OK if the request is a duplicate
     * (idempotency key already exists).
     *
     * Input:
     *   - idempotencyKey (body, required)
     *   - merchantId     (body, required)
     *   - amount         (body, required, > 0)
     *   - currency       (body, required, ISO 4217)
     *   - paymentMethod  (body, required, CARD | UPI)
     *
     * Output: PaymentResponse (see schema)
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request) {

        log.info("POST /api/v1/payments idempotencyKey={}", request.getIdempotencyKey());
        PaymentResponse response = paymentService.createPayment(request);

        HttpStatus status = response.getRetryCount() == 0 && response.getProviderReference() != null
                ? HttpStatus.CREATED
                : HttpStatus.OK;

        return ResponseEntity.status(status).body(response);
    }

    /**
     * Fetch a payment by its UUID.
     *
     * Input:  path variable {id} (UUID)
     * Output: PaymentResponse
     * Errors: 404 if payment not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID id) {
        log.info("GET /api/v1/payments/{}", id);
        return ResponseEntity.ok(paymentService.getPayment(id));
    }

    /**
     * List all payments for a merchant.
     *
     * Input:  query param merchantId (required)
     * Output: List<PaymentResponse>
     */
    @GetMapping
    public ResponseEntity<List<PaymentResponse>> getPaymentsByMerchant(
            @RequestParam String merchantId) {
        log.info("GET /api/v1/payments?merchantId={}", merchantId);
        return ResponseEntity.ok(paymentService.getPaymentsByMerchant(merchantId));
    }
}
