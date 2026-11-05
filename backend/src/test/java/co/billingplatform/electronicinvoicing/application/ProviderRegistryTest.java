package co.billingplatform.electronicinvoicing.application;

import co.billingplatform.electronicinvoicing.adapter.mock.MockElectronicInvoicingProvider;
import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.IdempotencyKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryTest {

    @Test
    @DisplayName("dos adaptadores con el mismo id son un error de configuración")
    void rejectsDuplicateProviderIds() {
        var a = new MockElectronicInvoicingProvider(r -> new byte[32], Clock.systemUTC(), d -> null);
        var b = new MockElectronicInvoicingProvider(r -> new byte[32], Clock.systemUTC(), d -> null);
        assertThrows(IllegalStateException.class, () -> new ProviderRegistry(List.of(a, b)));
    }

    @Test
    @DisplayName("la clave de idempotencia es estable por documento y distinta por tipo")
    void idempotencyKeyIsStablePerDocument() {
        UUID id = UUID.randomUUID();
        assertEquals(IdempotencyKey.forDocument(DocumentKind.INVOICE, id), IdempotencyKey.forDocument(DocumentKind.INVOICE, id));
        assertNotEquals(IdempotencyKey.forDocument(DocumentKind.INVOICE, id), IdempotencyKey.forDocument(DocumentKind.CREDIT_NOTE, id));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey("short"));
        assertThrows(IllegalArgumentException.class, () -> new IdempotencyKey("has spaces in it"));
    }
}
