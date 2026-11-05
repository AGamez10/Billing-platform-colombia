package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.invoicing.application.InMemoryAdapters.Accounts;
import co.billingplatform.invoicing.application.InMemoryAdapters.Invoices;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceFixtures;
import co.billingplatform.invoicing.domain.InvoiceLine;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.invoicing.domain.NumberingRange;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.TenantId;
import co.billingplatform.tax.domain.TestTaxRates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceLifecycleServiceTest {

    /** 2026-11-06T02:30Z = 5 de noviembre, 21:30 en Bogotá. */
    private static final Clock LATE_EVENING_IN_BOGOTA = Clock.fixed(Instant.parse("2026-11-06T02:30:00Z"), ZoneOffset.UTC);

    private final TenantId tenantA = new TenantId(UUID.randomUUID());
    private final TenantId tenantB = new TenantId(UUID.randomUUID());
    private final Actor actor = Actor.user(UUID.randomUUID());
    private final UUID customerOfA = UUID.randomUUID();

    private final Invoices invoices = new Invoices();
    private final Accounts accounts = new Accounts();
    private final Ranges ranges = new Ranges();
    private final List<UUID> queued = new ArrayList<>();
    private final Meter meter = new Meter();
    private final List<AuditTrail.Event> auditEvents = new ArrayList<>();

    private final CustomerDirectory customers = (tenant, id) ->
            tenant.equals(tenantA) && id.equals(customerOfA) ? Optional.of(InvoiceFixtures.customer()) : Optional.<Party>empty();

    private final InvoiceDraftService drafts = new InvoiceDraftService(invoices, customers, TestTaxRates.catalog(),
            InvoiceFixtures.CALCULATOR, auditEvents::add, LATE_EVENING_IN_BOGOTA);
    private final FinalizeInvoiceService finalizer = new FinalizeInvoiceService(invoices, ranges, accounts,
            (tenant, id) -> queued.add(id), meter, TestTaxRates.catalog(), InvoiceFixtures.CALCULATOR,
            auditEvents::add, LATE_EVENING_IN_BOGOTA);

    static final class Ranges implements NumberingRangeRepository {
        final Map<String, NumberingRange> active = new HashMap<>();
        int saves;

        void add(NumberingRange range) {
            active.put(range.tenantId() + ":" + range.environment(), range);
        }

        @Override
        public Optional<NumberingRange> lockActiveInvoiceRange(TenantId tenantId, ProviderEnvironment environment, LocalDate onDate) {
            return Optional.ofNullable(active.get(tenantId + ":" + environment));
        }

        @Override
        public void save(NumberingRange range) {
            saves++;
        }
    }

    static final class Meter implements UsageMeter {
        DomainException refuseWith;
        final List<UUID> recorded = new ArrayList<>();

        @Override
        public void ensureCanIssue(TenantId tenantId, ProviderEnvironment environment) {
            if (refuseWith != null) throw refuseWith;
        }

        @Override
        public void recordIssued(TenantId tenantId, UUID documentId, ProviderAccount account) {
            recorded.add(documentId);
        }
    }

    private InvoiceDraftService.DraftCommand command() {
        return new InvoiceDraftService.DraftCommand(customerOfA, InvoiceFixtures.lines());
    }

    private ProviderAccount configureHabilitation(TenantId tenant) {
        ProviderAccount account = accounts.activate(tenant, new ProviderId("alpha"), ProviderEnvironment.HABILITATION, null);
        ranges.add(InvoiceFixtures.range(tenant, ProviderEnvironment.HABILITATION));
        return account;
    }

    @Test
    @DisplayName("crear borrador: el servidor calcula, usa la fecha de Colombia y audita")
    void createsDraft() {
        Invoice draft = drafts.create(tenantA, actor, command());

        assertEquals(InvoiceStatus.DRAFT, draft.status());
        assertEquals(LocalDate.of(2026, 11, 5), draft.issueDate(), "21:30 en Bogotá sigue siendo 5 de noviembre aunque en UTC sea 6");
        assertEquals(new BigDecimal("397000.00"), draft.calculation().payableAmount());
        assertTrue(invoices.store.containsKey(draft.id()));
        assertEquals("INVOICE_DRAFT_CREATED", auditEvents.getLast().action());
    }

    @Test
    @DisplayName("no se puede facturar a un cliente de otra organización")
    void cannotUseForeignCustomer() {
        assertEquals("CUSTOMER_NOT_FOUND", assertThrows(DomainException.class,
                () -> drafts.create(tenantB, actor, command())).code());
    }

    @Test
    @DisplayName("editar y borrar solo aplica a borradores del propio tenant")
    void editAndDeleteDrafts() {
        Invoice draft = drafts.create(tenantA, actor, command());
        List<InvoiceLine> oneLine = List.of(InvoiceFixtures.lines().getFirst());

        Invoice updated = drafts.update(tenantA, actor, draft.id(), new InvoiceDraftService.DraftCommand(customerOfA, oneLine));
        assertEquals(new BigDecimal("357000.00"), updated.calculation().payableAmount());

        assertEquals("INVOICE_NOT_FOUND", assertThrows(DomainException.class,
                () -> drafts.delete(tenantB, actor, draft.id())).code());
        drafts.delete(tenantA, actor, draft.id());
        assertFalse(invoices.store.containsKey(draft.id()));
    }

    @Test
    @DisplayName("finalizar: numera, liga cuenta, encola el envío, registra consumo y audita")
    void finalizesDraft() {
        ProviderAccount account = configureHabilitation(tenantA);
        Invoice draft = drafts.create(tenantA, actor, command());

        Invoice result = finalizer.finalizeInvoice(tenantA, actor, draft.id());

        assertEquals(InvoiceStatus.PENDING_SUBMISSION, result.status());
        assertEquals("SETP990000000", result.number().orElseThrow().formatted());
        assertEquals(account.id(), result.providerAccountId().orElseThrow());
        assertEquals(LocalDate.of(2026, 11, 5), result.issueDate());
        assertEquals(List.of(draft.id()), queued);
        assertEquals(List.of(draft.id()), meter.recorded);
        assertEquals(1, ranges.saves);
        assertEquals("INVOICE_FINALIZED", auditEvents.getLast().action());
        assertEquals("HABILITATION", auditEvents.getLast().details().get("environment"));
    }

    @Test
    @DisplayName("finalizar dos veces no consume otro número ni encola otro envío")
    void finalizeIsIdempotent() {
        configureHabilitation(tenantA);
        Invoice draft = drafts.create(tenantA, actor, command());

        finalizer.finalizeInvoice(tenantA, actor, draft.id());
        Invoice again = finalizer.finalizeInvoice(tenantA, actor, draft.id());

        assertEquals("SETP990000000", again.number().orElseThrow().formatted());
        assertEquals(1, queued.size());
        assertEquals(1, meter.recorded.size());
    }

    @Test
    @DisplayName("sin proveedor, sin resolución del ambiente o sin cuota no se finaliza ni se consume número")
    void finalizePreconditions() {
        Invoice draft = drafts.create(tenantA, actor, command());
        assertEquals("NO_PROVIDER_CONFIGURED", assertThrows(DomainException.class,
                () -> finalizer.finalizeInvoice(tenantA, actor, draft.id())).code());

        accounts.activate(tenantA, new ProviderId("alpha"), ProviderEnvironment.PRODUCTION, null);
        ranges.add(InvoiceFixtures.range(tenantA, ProviderEnvironment.HABILITATION));
        assertEquals("NUMBERING_RANGE_NOT_FOUND", assertThrows(DomainException.class,
                () -> finalizer.finalizeInvoice(tenantA, actor, draft.id())).code(),
                "una resolución de habilitación no sirve para una cuenta de producción");

        NumberingRange production = InvoiceFixtures.range(tenantA, ProviderEnvironment.PRODUCTION);
        ranges.add(production);
        meter.refuseWith = new DomainException("DOCUMENT_QUOTA_EXCEEDED", "quota");
        assertEquals("DOCUMENT_QUOTA_EXCEEDED", assertThrows(DomainException.class,
                () -> finalizer.finalizeInvoice(tenantA, actor, draft.id())).code());

        assertEquals(InvoiceStatus.DRAFT, invoices.store.get(draft.id()).status());
        assertEquals(990000000, production.nextNumber());
        assertTrue(queued.isEmpty());
    }

    @Test
    @DisplayName("un tenant no puede finalizar el borrador de otro")
    void cannotFinalizeForeignDraft() {
        configureHabilitation(tenantA);
        configureHabilitation(tenantB);
        Invoice draftOfA = drafts.create(tenantA, actor, command());

        assertEquals("INVOICE_NOT_FOUND", assertThrows(DomainException.class,
                () -> finalizer.finalizeInvoice(tenantB, actor, draftOfA.id())).code());
        assertEquals(InvoiceStatus.DRAFT, draftOfA.status());
    }
}
