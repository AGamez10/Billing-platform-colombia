package co.billingplatform.payments.domain;

import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChargeTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());

    private Charge open(String payable) {
        return Charge.open(tenant, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal(payable));
    }

    @Test
    @DisplayName("se cobra en pesos enteros: el total con centavos se redondea al peso más cercano")
    void chargesWholePesos() {
        assertEquals(new BigDecimal("5950.00"), open("5950.00").amount());
        assertEquals(new BigDecimal("1250.00"), open("1249.50").amount());
        assertEquals(new BigDecimal("1249.00"), open("1249.49").amount());
        assertThrows(DomainException.class, () -> open("0.40"));
    }

    @Test
    @DisplayName("un estado final no cambia: avisos tardíos o duplicados no pagan dos veces")
    void finalStatusIsSticky() {
        Charge c = open("1000");
        assertFalse(c.apply(Charge.Status.PENDING, null, null, Instant.now()), "pendiente no es un cambio");
        assertTrue(c.apply(Charge.Status.PAID, "TX1", "BRE_B", Instant.now()));
        assertTrue(c.paidAt().isPresent());
        assertFalse(c.apply(Charge.Status.PAID, "TX1", "BRE_B", Instant.now()), "el segundo aviso de pago no cuenta");
        assertFalse(c.apply(Charge.Status.REJECTED, "TX2", "CARD", Instant.now()), "no se revierte un pago");
        assertEquals(Charge.Status.PAID, c.status());
        assertEquals("TX1", c.gatewayTransactionId().orElseThrow());
    }
}
