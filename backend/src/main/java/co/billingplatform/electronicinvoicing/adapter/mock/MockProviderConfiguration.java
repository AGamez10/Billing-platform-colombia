package co.billingplatform.electronicinvoicing.adapter.mock;

import co.billingplatform.electronicinvoicing.port.SecretResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Registra el proveedor simulado. Cada adaptador se registra a sí mismo: el núcleo y la
 * configuración central solo conocen el puerto {@code ElectronicInvoicingProvider}.
 * Solo opera en SIMULATION y marca sus claves con {@code SIMULATED-}.
 */
@Configuration
class MockProviderConfiguration {

    @Bean
    MockElectronicInvoicingProvider mockElectronicInvoicingProvider(
            SecretResolver secrets, Clock clock,
            @Value("${billing.providers.mock.behavior:ACCEPT}") MockElectronicInvoicingProvider.Behavior behavior) {
        return new MockElectronicInvoicingProvider(secrets, clock, document -> behavior);
    }
}
