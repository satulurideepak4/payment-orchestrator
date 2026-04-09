package com.yuno.payment.provider;

import com.yuno.payment.model.Payment;
import com.yuno.payment.model.PaymentMethod;

/**
 * Contract that every downstream payment provider connector must implement.
 *
 * The routing engine uses {@link #supports(PaymentMethod)} to select the
 * correct connector at runtime without switch/if chains.
 */
public interface PaymentProviderConnector {

    /**
     * Returns the human-readable name of this provider (e.g. "ProviderA").
     */
    String getName();

    /**
     * Returns true if this connector can handle the given payment method.
     */
    boolean supports(PaymentMethod method);

    /**
     * Submits the payment to the provider.
     *
     * @param payment the payment entity (read-only — do not persist inside this method)
     * @return a {@link ProviderResponse} containing success/failure info
     */
    ProviderResponse process(Payment payment);
}
