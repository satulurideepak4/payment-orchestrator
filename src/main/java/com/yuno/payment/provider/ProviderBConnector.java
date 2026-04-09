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
 * Provider B — handles UPI payments.
 *
 * Mirrors ProviderA in structure. UPI is the primary payment method for
 * India-based merchants. Simulates a 15% failure rate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderBConnector implements PaymentProviderConnector {

    private static final String PROVIDER_NAME = "ProviderB";
    private static final double FAILURE_RATE = 0.15;

    private final MeterRegistry meterRegistry;

    @Override
    public String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean supports(PaymentMethod method) {
        return PaymentMethod.UPI == method;
    }

    @Override
    public ProviderResponse process(Payment payment) {
        log.info("[ProviderB] Processing UPI payment id={} amount={} {}",
                payment.getId(), payment.getAmount(), payment.getCurrency());

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            simulateLatency();

            if (shouldFail()) {
                log.warn("[ProviderB] Simulated failure for payment id={}", payment.getId());
                meterRegistry.counter("provider.response", "provider", PROVIDER_NAME, "result", "failure").increment();
                return ProviderResponse.failure("PROVIDER_B_TIMEOUT", "UPI transaction timed out (simulated)");
            }

            String ref = "PB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            log.info("[ProviderB] Payment id={} succeeded, ref={}", payment.getId(), ref);
            meterRegistry.counter("provider.response", "provider", PROVIDER_NAME, "result", "success").increment();
            return ProviderResponse.success(ref, "UPI payment completed");

        } finally {
            sample.stop(meterRegistry.timer("provider.process.duration", "provider", PROVIDER_NAME));
        }
    }

    private boolean shouldFail() {
        return Math.random() < FAILURE_RATE;
    }

    private void simulateLatency() {
        try {
            long millis = 30 + (long) (Math.random() * 80);
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
