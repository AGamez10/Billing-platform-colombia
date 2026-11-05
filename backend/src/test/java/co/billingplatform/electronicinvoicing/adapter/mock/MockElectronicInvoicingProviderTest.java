package co.billingplatform.electronicinvoicing.adapter.mock;

import co.billingplatform.electronicinvoicing.domain.InboundWebhook;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.WebhookVerificationException;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MockElectronicInvoicingProviderTest {

    private static ProviderAccount account(ProviderEnvironment env) {
        return new ProviderAccount(UUID.randomUUID(), new TenantId(UUID.randomUUID()), MockElectronicInvoicingProvider.ID,
                env, "secret://creds", "secret://webhook");
    }

    @Test
    @DisplayName("el adaptador simulado se niega a operar fuera de SIMULATION")
    void refusesNonSimulationAccounts() {
        var mock = new MockElectronicInvoicingProvider(r -> new byte[32], Clock.systemUTC(), d -> MockElectronicInvoicingProvider.Behavior.ACCEPT);
        assertThrows(IllegalStateException.class, () -> mock.queryStatus(account(ProviderEnvironment.PRODUCTION),
                new co.billingplatform.electronicinvoicing.domain.ExternalReference("x")));
        assertFalse(mock.capabilities().supports(ProviderEnvironment.PRODUCTION));
        assertFalse(mock.capabilities().supports(ProviderEnvironment.HABILITATION));
    }

    @Test
    @DisplayName("un secreto de webhook corto o ausente invalida la verificación")
    void rejectsWeakWebhookSecret() {
        var mock = new MockElectronicInvoicingProvider(r -> new byte[8], Clock.systemUTC(), d -> null);
        InboundWebhook webhook = new InboundWebhook(Map.of("X-Mock-Timestamp", List.of("1"), "X-Mock-Signature", List.of("00")),
                new byte[0], Instant.EPOCH);
        assertThrows(WebhookVerificationException.class, () -> mock.parseWebhook(account(ProviderEnvironment.SIMULATION), webhook));
    }

    @Test
    @DisplayName("ProviderAccount nunca imprime referencias de secretos")
    void accountToStringHasNoSecrets() {
        String text = account(ProviderEnvironment.SIMULATION).toString();
        assertFalse(text.contains("secret://"));
    }
}
