package co.billingplatform.invoicing.domain;

import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.shared.domain.IdentificationType;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.PartyIdentification;
import co.billingplatform.shared.domain.RoundingPolicy;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.tax.domain.DocumentCalculator;
import co.billingplatform.tax.domain.TestTaxRates;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class InvoiceFixtures {

    public static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    public static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    public static final DocumentCalculator CALCULATOR = new DocumentCalculator(RoundingPolicy.COP_DEFAULT);

    private InvoiceFixtures() {}

    public static Party customer() {
        return new Party(PartyIdentification.of(IdentificationType.CEDULA_CIUDADANIA, "1020304050"),
                "Cliente de Prueba", "cliente@example.com");
    }

    public static Party issuer() {
        return new Party(PartyIdentification.nit("800197268", 4), "Emisor de Prueba S.A.S.", "facturacion@example.com");
    }

    public static List<InvoiceLine> lines() {
        return List.of(
                new InvoiceLine(null, "Servicio de consultoría", "94", new BigDecimal("2"), new BigDecimal("150000"), null, List.of("IVA_19")),
                new InvoiceLine(null, "Libro", "94", new BigDecimal("1"), new BigDecimal("40000"), null, List.of("IVA_EXCLUIDO")));
    }

    public static Invoice draft(TenantId tenant) {
        return Invoice.draft(UUID.randomUUID(), tenant, customer(), lines(), TODAY, TestTaxRates.catalog(), CALCULATOR, NOW);
    }

    public static ProviderAccount account(TenantId tenant, ProviderEnvironment environment) {
        return new ProviderAccount(UUID.randomUUID(), tenant, new ProviderId("alpha"), environment,
                "secret://creds", "secret://webhook");
    }

    public static NumberingRange range(TenantId tenant) {
        return range(tenant, ProviderEnvironment.HABILITATION);
    }

    public static NumberingRange range(TenantId tenant, ProviderEnvironment environment) {
        return NumberingRange.create(tenant, environment, "18760000001", "SETP", 990000000, 995000000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31));
    }

    /** Finalizada con una cuenta de proveedor de habilitación. */
    public static Invoice finalized(TenantId tenant) {
        return finalized(tenant, account(tenant, ProviderEnvironment.HABILITATION));
    }

    public static Invoice finalized(TenantId tenant, ProviderAccount account) {
        Invoice invoice = draft(tenant);
        invoice.finalizeWith(range(tenant, account.environment()), account, TODAY, TestTaxRates.catalog(), CALCULATOR, NOW);
        return invoice;
    }
}
