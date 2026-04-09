package com.yuno.payment.routing;

import com.yuno.payment.exception.NoProviderFoundException;
import com.yuno.payment.model.PaymentMethod;
import com.yuno.payment.provider.PaymentProviderConnector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Routing Engine — selects the correct provider connector for a given payment method.
 *
 * Routing rules (from the assignment spec):
 *   CARD → ProviderA
 *   UPI  → ProviderB
 *
 * The engine is open for extension: adding a new PaymentMethod only requires
 * registering a new {@link PaymentProviderConnector} bean — no code change needed here.
 */
@Slf4j
@Component
public class RoutingEngine {

    private final List<PaymentProviderConnector> connectors;

    /**
     * Spring injects all beans implementing {@link PaymentProviderConnector}.
     * The order in the list follows the bean registration order (A before B).
     */
    public RoutingEngine(List<PaymentProviderConnector> connectors) {
        this.connectors = connectors;
        log.info("RoutingEngine initialised with {} connectors: {}",
                connectors.size(),
                connectors.stream().map(PaymentProviderConnector::getName).toList());
    }

    /**
     * Returns the first connector that supports the given payment method.
     *
     * @param method the payment method to route
     * @return the matching connector
     * @throws NoProviderFoundException if no connector supports this method
     */
    public PaymentProviderConnector route(PaymentMethod method) {
        log.debug("Routing payment method={}", method);
        return connectors.stream()
                .filter(c -> c.supports(method))
                .findFirst()
                .orElseThrow(() -> new NoProviderFoundException(
                        "No provider found for payment method: " + method));
    }
}
