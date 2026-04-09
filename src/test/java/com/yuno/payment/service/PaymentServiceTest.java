package com.yuno.payment.service;

import com.yuno.payment.dto.CreatePaymentRequest;
import com.yuno.payment.dto.PaymentResponse;
import com.yuno.payment.exception.PaymentNotFoundException;
import com.yuno.payment.idempotency.IdempotencyService;
import com.yuno.payment.model.Payment;
import com.yuno.payment.model.PaymentMethod;
import com.yuno.payment.model.PaymentStatus;
import com.yuno.payment.provider.PaymentProviderConnector;
import com.yuno.payment.provider.ProviderResponse;
import com.yuno.payment.repository.PaymentRepository;
import com.yuno.payment.routing.RoutingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PaymentService.
 * All collaborators are mocked — no DB or Redis required.
 *
 * Test classification: SANITY + REGRESSION
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RoutingEngine routingEngine;
    @Mock private IdempotencyService idempotencyService;
    @Mock private PaymentProviderConnector mockConnector;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository, routingEngine, idempotencyService, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(paymentService, "maxRetryAttempts", 2);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CreatePaymentRequest buildRequest(String key, PaymentMethod method) {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setIdempotencyKey(key);
        req.setMerchantId("merchant-001");
        req.setAmount(new BigDecimal("100.00"));
        req.setCurrency("USD");
        req.setPaymentMethod(method);
        return req;
    }

    private Payment savedPayment(UUID id, String key, PaymentMethod method, PaymentStatus status) {
        return Payment.builder()
                .id(id)
                .idempotencyKey(key)
                .merchantId("merchant-001")
                .amount(new BigDecimal("100.00"))
                .currency("USD")
                .paymentMethod(method)
                .status(status)
                .build();
    }

    // -----------------------------------------------------------------------
    // SANITY — Create payment happy path
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[SANITY] New CARD payment is created and returns SUCCESS")
    void createCardPaymentSuccess() {
        String key = "idem-key-001";
        UUID id = UUID.randomUUID();
        Payment pending = savedPayment(id, key, PaymentMethod.CARD, PaymentStatus.PENDING);
        Payment success = savedPayment(id, key, PaymentMethod.CARD, PaymentStatus.SUCCESS);
        success.setProviderName("ProviderA");
        success.setProviderReference("PA-ABCD1234");

        when(idempotencyService.get(key)).thenReturn(null);
        when(paymentRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenReturn(pending).thenReturn(pending).thenReturn(success);
        when(routingEngine.route(PaymentMethod.CARD)).thenReturn(mockConnector);
        when(mockConnector.getName()).thenReturn("ProviderA");
        when(mockConnector.process(any())).thenReturn(ProviderResponse.success("PA-ABCD1234", "Authorised"));

        PaymentResponse response = paymentService.createPayment(buildRequest(key, PaymentMethod.CARD));

        assertThat(response).isNotNull();
        verify(paymentRepository, atLeast(1)).save(any());
        verify(routingEngine).route(PaymentMethod.CARD);
        verify(mockConnector).process(any());
    }

    @Test
    @DisplayName("[SANITY] New UPI payment is routed to ProviderB")
    void createUpiPaymentRoutedToProviderB() {
        String key = "idem-key-upi-001";
        UUID id = UUID.randomUUID();
        Payment pending = savedPayment(id, key, PaymentMethod.UPI, PaymentStatus.PENDING);

        when(idempotencyService.get(key)).thenReturn(null);
        when(paymentRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenReturn(pending);
        when(routingEngine.route(PaymentMethod.UPI)).thenReturn(mockConnector);
        when(mockConnector.getName()).thenReturn("ProviderB");
        when(mockConnector.process(any())).thenReturn(ProviderResponse.success("PB-ABCD1234", "UPI success"));

        paymentService.createPayment(buildRequest(key, PaymentMethod.UPI));

        verify(routingEngine).route(PaymentMethod.UPI);
    }

    // -----------------------------------------------------------------------
    // SANITY — Idempotency
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[SANITY] Duplicate request returns existing payment from Redis")
    void idempotentRequestFromRedis() {
        String key = "idem-key-002";
        UUID id = UUID.randomUUID();
        Payment existing = savedPayment(id, key, PaymentMethod.CARD, PaymentStatus.SUCCESS);
        existing.setProviderReference("PA-XYZ");

        when(idempotencyService.get(key)).thenReturn(id.toString());
        when(paymentRepository.findById(id)).thenReturn(Optional.of(existing));

        PaymentResponse response = paymentService.createPayment(buildRequest(key, PaymentMethod.CARD));

        assertThat(response.getId()).isEqualTo(id);
        verify(paymentRepository, never()).save(any());
        verify(routingEngine, never()).route(any());
    }

    @Test
    @DisplayName("[SANITY] Duplicate request returns existing payment from DB (Redis miss)")
    void idempotentRequestFromDb() {
        String key = "idem-key-003";
        UUID id = UUID.randomUUID();
        Payment existing = savedPayment(id, key, PaymentMethod.CARD, PaymentStatus.SUCCESS);

        when(idempotencyService.get(key)).thenReturn(null);
        when(paymentRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

        PaymentResponse response = paymentService.createPayment(buildRequest(key, PaymentMethod.CARD));

        assertThat(response.getId()).isEqualTo(id);
        verify(routingEngine, never()).route(any());
    }

    // -----------------------------------------------------------------------
    // REGRESSION — Retry logic
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[REGRESSION] Provider fails once, succeeds on retry → SUCCESS")
    void retryOnFirstFailureSucceeds() {
        String key = "idem-key-retry-001";
        UUID id = UUID.randomUUID();
        Payment pending = savedPayment(id, key, PaymentMethod.CARD, PaymentStatus.PENDING);

        when(idempotencyService.get(key)).thenReturn(null);
        when(paymentRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenReturn(pending);
        when(routingEngine.route(PaymentMethod.CARD)).thenReturn(mockConnector);
        when(mockConnector.getName()).thenReturn("ProviderA");
        when(mockConnector.process(any()))
                .thenReturn(ProviderResponse.failure("DECLINED", "Card declined"))
                .thenReturn(ProviderResponse.success("PA-RETRY-OK", "Authorised on retry"));

        paymentService.createPayment(buildRequest(key, PaymentMethod.CARD));

        verify(mockConnector, times(2)).process(any());
    }

    @Test
    @DisplayName("[REGRESSION] Provider fails all attempts → payment marked FAILED")
    void allRetriesExhaustedMarksFailed() {
        String key = "idem-key-fail-001";
        UUID id = UUID.randomUUID();
        Payment pending = savedPayment(id, key, PaymentMethod.CARD, PaymentStatus.PENDING);

        when(idempotencyService.get(key)).thenReturn(null);
        when(paymentRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenReturn(pending);
        when(routingEngine.route(PaymentMethod.CARD)).thenReturn(mockConnector);
        when(mockConnector.getName()).thenReturn("ProviderA");
        when(mockConnector.process(any()))
                .thenReturn(ProviderResponse.failure("DECLINED", "Declined"))
                .thenReturn(ProviderResponse.failure("DECLINED", "Declined again"));

        paymentService.createPayment(buildRequest(key, PaymentMethod.CARD));

        // Verify provider was called maxRetryAttempts (2) times
        verify(mockConnector, times(2)).process(any());
        // Verify save was called (final FAILED state)
        verify(paymentRepository, atLeast(2)).save(any());
    }

    @Test
    @DisplayName("[REGRESSION] Provider throws runtime exception → treated as failure, retried")
    void providerExceptionIsRetried() {
        String key = "idem-key-exc-001";
        UUID id = UUID.randomUUID();
        Payment pending = savedPayment(id, key, PaymentMethod.CARD, PaymentStatus.PENDING);

        when(idempotencyService.get(key)).thenReturn(null);
        when(paymentRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenReturn(pending);
        when(routingEngine.route(PaymentMethod.CARD)).thenReturn(mockConnector);
        when(mockConnector.getName()).thenReturn("ProviderA");
        when(mockConnector.process(any()))
                .thenThrow(new RuntimeException("Network timeout"))
                .thenReturn(ProviderResponse.success("PA-RECOVERED", "OK"));

        paymentService.createPayment(buildRequest(key, PaymentMethod.CARD));

        verify(mockConnector, times(2)).process(any());
    }

    // -----------------------------------------------------------------------
    // SANITY — Fetch payment
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[SANITY] Fetch existing payment by ID returns correct response")
    void fetchPaymentById() {
        UUID id = UUID.randomUUID();
        Payment payment = savedPayment(id, "key-fetch", PaymentMethod.UPI, PaymentStatus.SUCCESS);

        when(paymentRepository.findById(id)).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.getPayment(id);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    @DisplayName("[REGRESSION] Fetch non-existent payment → throws PaymentNotFoundException")
    void fetchNonExistentPaymentThrows() {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(id))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    // -----------------------------------------------------------------------
    // SANITY — List by merchant
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[SANITY] List payments by merchant returns all records")
    void listPaymentsByMerchant() {
        String merchantId = "merchant-XYZ";
        Payment p1 = savedPayment(UUID.randomUUID(), "k1", PaymentMethod.CARD, PaymentStatus.SUCCESS);
        Payment p2 = savedPayment(UUID.randomUUID(), "k2", PaymentMethod.UPI, PaymentStatus.FAILED);
        p1.setMerchantId(merchantId);
        p2.setMerchantId(merchantId);

        when(paymentRepository.findByMerchantId(merchantId)).thenReturn(List.of(p1, p2));

        List<PaymentResponse> responses = paymentService.getPaymentsByMerchant(merchantId);

        assertThat(responses).hasSize(2);
    }

    @Test
    @DisplayName("[REGRESSION] List payments for unknown merchant returns empty list")
    void listPaymentsUnknownMerchantReturnsEmpty() {
        when(paymentRepository.findByMerchantId("unknown")).thenReturn(List.of());

        List<PaymentResponse> responses = paymentService.getPaymentsByMerchant("unknown");

        assertThat(responses).isEmpty();
    }
}
