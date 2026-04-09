package com.yuno.payment.provider;

import com.yuno.payment.model.Payment;
import com.yuno.payment.model.PaymentMethod;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Provider A — handles CARD payments.
 *
 * In production this would be an HTTP client to a real PSP (e.g. Stripe, Adyen).
 * Here we simulate processing with a configurable failure rate so retry/failover
 * logic can be exercised in tests.
 *
 * Simulated success rate: 80% (controlled by {@code PROVIDER_A_FAILURE_RATE}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderAConnector implements PaymentProviderConnector {

    private static final String PROVIDER_NAME = "ProviderA";

    /**
     * Simulate 20% failure rate. In real code this would call an external API.
     * Set to 0.0 to make all calls succeed; 1.0 to make all fail.
     */
    private static final double FAILURE_RATE = 0.2;

    private final MeterRegistry meterRegistry;

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supports(PaymentMethod method) {
        return PaymentMethod.CARD == method;
    }

    @Override
    public ProviderResponse process(Payment payment) {
        log.info("[ProviderA] Processing CARD payment id={} amount={} {}",
                payment.getId(), payment.getAmount(), payment.getCurrency());

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Simulate network latency (50-150ms)
            simulateLatency();

            if (shouldFail()) {
                log.warn("[ProviderA] Simulated failure for payment id={}", payment.getId());
                meterRegistry.counter("provider.response", "provider", PROVIDER_NAME, "result", "failure").increment();
                return ProviderResponse.failure("PROVIDER_A_DECLINED", "Card declined by issuer (simulated)");
            }

            String ref = "PA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            log.info("[ProviderA] Payment id={} succeeded, ref={}", payment.getId(), ref);
            meterRegistry.counter("provider.response", "provider", PROVIDER_NAME, "result", "success").increment();
            return ProviderResponse.success(ref, "Card payment authorised");

        } finally {
            sample.stop(meterRegistry.timer("provider.process.duration", "provider", PROVIDER_NAME));
        }
    }

    private boolean shouldFail() {
        return Math.random() < FAILURE_RATE;
    }

    private void simulateLatency() {
        try {
            long millis = 50 + (long) (Math.random() * 100);
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
