package com.yuno.payment.routing;

import com.yuno.payment.exception.NoProviderFoundException;
import com.yuno.payment.model.PaymentMethod;
import com.yuno.payment.provider.PaymentProviderConnector;
import com.yuno.payment.provider.ProviderAConnector;
import com.yuno.payment.provider.ProviderBConnector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the RoutingEngine.
 *
 * Test classification: SANITY + REGRESSION
 */
class RoutingEngineTest {

    private RoutingEngine routingEngine;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        List<PaymentProviderConnector> connectors = List.of(
                new ProviderAConnector(meterRegistry),
                new ProviderBConnector(meterRegistry)
        );
        routingEngine = new RoutingEngine(connectors);
    }

    // -----------------------------------------------------------------------
    // SANITY — Happy path routing
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[SANITY] CARD payment routes to ProviderA")
    void cardRoutesToProviderA() {
        PaymentProviderConnector connector = routingEngine.route(PaymentMethod.CARD);
        assertThat(connector.getName()).isEqualTo("ProviderA");
    }

    @Test
    @DisplayName("[SANITY] UPI payment routes to ProviderB")
    void upiRoutesToProviderB() {
        PaymentProviderConnector connector = routingEngine.route(PaymentMethod.UPI);
        assertThat(connector.getName()).isEqualTo("ProviderB");
    }

    @Test
    @DisplayName("[SANITY] ProviderA supports CARD but not UPI")
    void providerASupportsOnlyCard() {
        ProviderAConnector providerA = new ProviderAConnector(meterRegistry);
        assertThat(providerA.supports(PaymentMethod.CARD)).isTrue();
        assertThat(providerA.supports(PaymentMethod.UPI)).isFalse();
    }

    @Test
    @DisplayName("[SANITY] ProviderB supports UPI but not CARD")
    void providerBSupportsOnlyUpi() {
        ProviderBConnector providerB = new ProviderBConnector(meterRegistry);
        assertThat(providerB.supports(PaymentMethod.UPI)).isTrue();
        assertThat(providerB.supports(PaymentMethod.CARD)).isFalse();
    }

    // -----------------------------------------------------------------------
    // REGRESSION — routing with empty/partial connector lists
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("[REGRESSION] No connectors registered → throws NoProviderFoundException")
    void noConnectorsThrows() {
        RoutingEngine emptyEngine = new RoutingEngine(List.of());
        assertThatThrownBy(() -> emptyEngine.route(PaymentMethod.CARD))
                .isInstanceOf(NoProviderFoundException.class)
                .hasMessageContaining("CARD");
    }

    @Test
    @DisplayName("[REGRESSION] Only ProviderB registered, CARD request → throws NoProviderFoundException")
    void missingCardProviderThrows() {
        RoutingEngine partialEngine = new RoutingEngine(
                List.of(new ProviderBConnector(meterRegistry)));
        assertThatThrownBy(() -> partialEngine.route(PaymentMethod.CARD))
                .isInstanceOf(NoProviderFoundException.class);
    }

    @Test
    @DisplayName("[REGRESSION] Only ProviderA registered, UPI request → throws NoProviderFoundException")
    void missingUpiProviderThrows() {
        RoutingEngine partialEngine = new RoutingEngine(
                List.of(new ProviderAConnector(meterRegistry)));
        assertThatThrownBy(() -> partialEngine.route(PaymentMethod.UPI))
                .isInstanceOf(NoProviderFoundException.class);
    }
}
