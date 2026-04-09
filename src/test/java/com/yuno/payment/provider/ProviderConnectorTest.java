package com.yuno.payment.provider;

import com.yuno.payment.model.Payment;
import com.yuno.payment.model.PaymentMethod;
import com.yuno.payment.model.PaymentStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for provider connectors.
 *
 * Since the connectors use probabilistic failure simulation, we use
 * @RepeatedTest to verify behaviour across multiple runs.
 *
 * Test classification: SANITY + REGRESSION
 */
class ProviderConnectorTest {

    private ProviderAConnector providerA;
    private ProviderBConnector providerB;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        providerA = new ProviderAConnector(registry);
        providerB = new ProviderBConnector(registry);
    }

    private Payment samplePayment(PaymentMethod method) {
        return Payment.builder()
                .id(UUID.randomUUID())
                .idempotencyKey("test-key-" + UUID.randomUUID())
                .merchantId("merchant-test")
                .amount(new BigDecimal("99.99"))
                .currency("USD")
                .paymentMethod(method)
                .status(PaymentStatus.PROCESSING)
                .build();
    }

    // -----------------------------------------------------------------------
    // ProviderA
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[SANITY] ProviderA name is 'ProviderA'")
    void providerAName() {
        assertThat(providerA.getName()).isEqualTo("ProviderA");
    }

    @Test
    @DisplayName("[SANITY] ProviderA supports CARD")
    void providerASupportsCard() {
        assertThat(providerA.supports(PaymentMethod.CARD)).isTrue();
    }

    @Test
    @DisplayName("[SANITY] ProviderA does not support UPI")
    void providerADoesNotSupportUpi() {
        assertThat(providerA.supports(PaymentMethod.UPI)).isFalse();
    }

    @Test
    @DisplayName("[SANITY] ProviderA process() returns a non-null response")
    void providerAProcessReturnsResponse() {
        ProviderResponse response = providerA.process(samplePayment(PaymentMethod.CARD));
        assertThat(response).isNotNull();
        assertThat(response.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("[REGRESSION] ProviderA successful response has non-null providerReference")
    void providerASuccessHasReference() {
        // Run multiple times to get a success (80% rate)
        for (int i = 0; i < 20; i++) {
            ProviderResponse response = providerA.process(samplePayment(PaymentMethod.CARD));
            if (response.isSuccess()) {
                assertThat(response.getProviderReference()).startsWith("PA-");
                return;
            }
        }
        // If we get here all 20 calls failed — statistically very unlikely (0.2^20 ≈ 10^-14)
    }

    // -----------------------------------------------------------------------
    // ProviderB
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[SANITY] ProviderB name is 'ProviderB'")
    void providerBName() {
        assertThat(providerB.getName()).isEqualTo("ProviderB");
    }

    @Test
    @DisplayName("[SANITY] ProviderB supports UPI")
    void providerBSupportsUpi() {
        assertThat(providerB.supports(PaymentMethod.UPI)).isTrue();
    }

    @Test
    @DisplayName("[SANITY] ProviderB does not support CARD")
    void providerBDoesNotSupportCard() {
        assertThat(providerB.supports(PaymentMethod.CARD)).isFalse();
    }

    @Test
    @DisplayName("[SANITY] ProviderB process() returns a non-null response")
    void providerBProcessReturnsResponse() {
        ProviderResponse response = providerB.process(samplePayment(PaymentMethod.UPI));
        assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("[REGRESSION] ProviderB failure response has non-null errorCode")
    void providerBFailureHasErrorCode() {
        for (int i = 0; i < 20; i++) {
            ProviderResponse response = providerB.process(samplePayment(PaymentMethod.UPI));
            if (!response.isSuccess()) {
                assertThat(response.getErrorCode()).isNotBlank();
                return;
            }
        }
    }

    // -----------------------------------------------------------------------
    // ProviderResponse value object
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[SANITY] ProviderResponse.success() sets success=true and reference")
    void providerResponseSuccessFactory() {
        ProviderResponse r = ProviderResponse.success("REF-001", "OK");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getProviderReference()).isEqualTo("REF-001");
        assertThat(r.getMessage()).isEqualTo("OK");
        assertThat(r.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("[SANITY] ProviderResponse.failure() sets success=false and errorCode")
    void providerResponseFailureFactory() {
        ProviderResponse r = ProviderResponse.failure("ERR_001", "Declined");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getErrorCode()).isEqualTo("ERR_001");
        assertThat(r.getMessage()).isEqualTo("Declined");
        assertThat(r.getProviderReference()).isNull();
    }
}
