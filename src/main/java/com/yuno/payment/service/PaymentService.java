package com.yuno.payment.service;

import com.yuno.payment.dto.CreatePaymentRequest;
import com.yuno.payment.dto.PaymentResponse;
import com.yuno.payment.exception.DuplicateIdempotencyKeyException;
import com.yuno.payment.exception.PaymentNotFoundException;
import com.yuno.payment.idempotency.IdempotencyService;
import com.yuno.payment.model.Payment;
import com.yuno.payment.model.PaymentStatus;
import com.yuno.payment.provider.PaymentProviderConnector;
import com.yuno.payment.provider.ProviderResponse;
import com.yuno.payment.repository.PaymentRepository;
import com.yuno.payment.routing.RoutingEngine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Orchestration Engine — the heart of the payment system.
 *
 * Responsibilities:
 *   1. Idempotency check (Redis first, then DB)
 *   2. Persist payment in PENDING state
 *   3. Route to the correct provider via RoutingEngine
 *   4. Execute with retry + failover logic
 *   5. Update payment status and persist the result
 *   6. Record metrics
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final RoutingEngine routingEngine;
    private final IdempotencyService idempotencyService;
    private final MeterRegistry meterRegistry;

    @Value("${payment.retry.max-attempts:3}")
    private int maxRetryAttempts;

    // -------------------------------------------------------------------------
    // Create Payment
    // -------------------------------------------------------------------------

    /**
     * Creates and processes a new payment.
     *
     * Idempotency guarantee: if the same idempotencyKey is received again
     * (from Redis or DB) the existing payment is returned without reprocessing.
     *
     * @param request validated payment request
     * @return the payment response (new or cached)
     */
    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        log.info("createPayment: key={} method={} amount={} {}",
                request.getIdempotencyKey(), request.getPaymentMethod(),
                request.getAmount(), request.getCurrency());

        // 1. Idempotency check — Redis
        String existingId = idempotencyService.get(request.getIdempotencyKey());
        if (existingId != null) {
            log.info("Idempotent request detected (Redis hit) for key={}", request.getIdempotencyKey());
            return paymentRepository.findById(UUID.fromString(existingId))
                    .map(PaymentResponse::from)
                    .orElseGet(() -> fetchFromDbByKey(request.getIdempotencyKey()));
        }

        // 2. Idempotency check — DB (covers Redis eviction edge case)
        var existing = paymentRepository.findByIdempotencyKey(request.getIdempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent request detected (DB hit) for key={}", request.getIdempotencyKey());
            Payment p = existing.get();
            idempotencyService.store(p.getIdempotencyKey(), p.getId().toString()); // re-warm Redis
            return PaymentResponse.from(p);
        }

        // 3. Persist in PENDING state
        Payment payment = Payment.builder()
                .idempotencyKey(request.getIdempotencyKey())
                .merchantId(request.getMerchantId())
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .paymentMethod(request.getPaymentMethod())
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        payment = paymentRepository.save(payment);

        // 4. Route and process with retry/failover
        payment = processWithRetry(payment);
        idempotencyService.store(payment.getIdempotencyKey(), payment.getId().toString());

        // 5. Persist final state
        payment.setUpdatedAt(LocalDateTime.now());
        payment = paymentRepository.save(payment);
        log.info("Payment id={} finalised with status={}", payment.getId(), payment.getStatus());

        return PaymentResponse.from(payment);
    }

    // -------------------------------------------------------------------------
    // Fetch Payment
    // -------------------------------------------------------------------------

    /**
     * Retrieves a payment by its UUID.
     */
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
    }

    /**
     * Retrieves all payments for a merchant.
     */
    @Transactional(readOnly = true)
    public List<PaymentResponse> getPaymentsByMerchant(String merchantId) {
        return paymentRepository.findByMerchantId(merchantId)
                .stream()
                .map(PaymentResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Retry & Failover
    // -------------------------------------------------------------------------

    /**
     * Attempts to process the payment up to {@code maxRetryAttempts} times.
     *
     * On each attempt:
     *   - The payment is set to PROCESSING
     *   - The routing engine selects the provider
     *   - The provider connector is called
     *   - On failure, retryCount is incremented and the loop continues
     *   - After all retries are exhausted, the payment is marked FAILED
     *
     * Failover: if a provider throws an unexpected exception (network down,
     * timeout etc.) it is caught and treated as a failure, allowing the retry
     * loop to attempt again.
     */
    private Payment processWithRetry(Payment payment) {
        PaymentProviderConnector connector = routingEngine.route(payment.getPaymentMethod());
        log.info("Routed payment id={} to provider={}", payment.getId(), connector.getName());

        payment.setProviderName(connector.getName());

        Timer.Sample timerSample = Timer.start(meterRegistry);

        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            log.info("Processing attempt {}/{} for payment id={}", attempt, maxRetryAttempts, payment.getId());

            payment.setStatus(PaymentStatus.PROCESSING);
            payment.setRetryCount(attempt - 1);
            paymentRepository.save(payment); // persist intermediate state

            try {
                ProviderResponse response = connector.process(payment);

                if (response.isSuccess()) {
                    payment.setStatus(PaymentStatus.SUCCESS);
                    payment.setProviderReference(response.getProviderReference());
                    payment.setStatusMessage(response.getMessage());
                    meterRegistry.counter("payment.processed",
                            "status", "success", "provider", connector.getName()).increment();
                    timerSample.stop(meterRegistry.timer("payment.processing.duration",
                            "provider", connector.getName()));
                    return payment;
                }

                log.warn("Provider {} failed attempt {}/{}: {}",
                        connector.getName(), attempt, maxRetryAttempts, response.getMessage());
                payment.setStatusMessage(response.getMessage());
                payment.setRetryCount(attempt);

                meterRegistry.counter("payment.retry",
                        "provider", connector.getName(), "attempt", String.valueOf(attempt)).increment();

            } catch (Exception e) {
                log.error("Unexpected error from provider {} on attempt {}/{}: {}",
                        connector.getName(), attempt, maxRetryAttempts, e.getMessage(), e);
                payment.setStatusMessage("Provider error: " + e.getMessage());
                payment.setRetryCount(attempt);
            }

            // Exponential backoff between retries (100ms * 2^attempt)
            if (attempt < maxRetryAttempts) {
                backoff(attempt);
            }
        }

        // All attempts exhausted
        payment.setStatus(PaymentStatus.FAILED);
        meterRegistry.counter("payment.processed",
                "status", "failed", "provider", connector.getName()).increment();
        timerSample.stop(meterRegistry.timer("payment.processing.duration",
                "provider", connector.getName()));

        log.error("Payment id={} FAILED after {} attempts", payment.getId(), maxRetryAttempts);
        return payment;
    }

    private void backoff(int attempt) {
        try {
            long delay = 100L * (1L << attempt); // 200ms, 400ms, 800ms...
            log.debug("Backing off for {}ms before next retry", delay);
            Thread.sleep(Math.min(delay, 2000)); // cap at 2s
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PaymentResponse fetchFromDbByKey(String idempotencyKey) {
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new PaymentNotFoundException(
                        "Payment not found for idempotency key: " + idempotencyKey));
    }
}
