package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.adapter.mock.MockElectronicInvoicingProvider;
import co.billingplatform.electronicinvoicing.adapter.mock.MockElectronicInvoicingProvider.Behavior;
import co.billingplatform.electronicinvoicing.application.ProviderRegistry;
import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ExternalReference;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.SubmissionResult;
import co.billingplatform.electronicinvoicing.domain.ValidationEvidence;
import co.billingplatform.electronicinvoicing.port.ElectronicInvoicingProvider;
import co.billingplatform.invoicing.application.InMemoryAdapters.Accounts;
import co.billingplatform.invoicing.application.InMemoryAdapters.Invoices;
import co.billingplatform.invoicing.application.InMemoryAdapters.ScriptedProvider;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceFixtures;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SubmitInvoiceServiceTest {

    private static final Clock CLOCK = Clock.fixed(InvoiceFixtures.NOW, ZoneOffset.UTC);
    private static final Set<DocumentKind> INVOICES = Set.of(DocumentKind.INVOICE);

    private final TenantId tenantA = new TenantId(UUID.randomUUID());
    private final TenantId tenantB = new TenantId(UUID.randomUUID());
    private final Invoices invoices = new Invoices();
    private final Accounts accounts = new Accounts();
    private final AtomicReference<Behavior> mockBehavior = new AtomicReference<>(Behavior.ACCEPT);
    private final MockElectronicInvoicingProvider mock = new MockElectronicInvoicingProvider(
            ref -> new byte[32], CLOCK, doc -> mockBehavior.get());

    private SubmitInvoiceService service(ElectronicInvoicingProvider... providers) {
        return new SubmitInvoiceService(invoices, accounts, new ProviderRegistry(List.of(providers)),
                tenant -> InvoiceFixtures.issuer(), CLOCK, 3);
    }

    private Invoice storedFinalizedInvoice(TenantId tenant) {
        Invoice invoice = InvoiceFixtures.finalized(tenant);
        invoices.save(invoice);
        return invoice;
    }

    @Test
    @DisplayName("flujo completo con el proveedor simulado: validada, pero marcada como simulación")
    void happyPathWithMock() {
        accounts.activate(tenantA, MockElectronicInvoicingProvider.ID, ProviderEnvironment.SIMULATION, null);
        Invoice invoice = storedFinalizedInvoice(tenantA);

        Invoice result = service(mock).submit(tenantA, invoice.id());

        assertEquals(InvoiceStatus.ACCEPTED, result.status());
        ValidationEvidence evidence = result.evidence().orElseThrow();
        assertEquals(ProviderEnvironment.SIMULATION, evidence.environment());
        assertTrue(evidence.documentKey().startsWith(MockElectronicInvoicingProvider.SIMULATED_KEY_PREFIX));
        assertFalse(result.isLegallyValid(), "un documento simulado nunca es una factura electrónica válida");
    }

    @Test
    @DisplayName("invocar el envío dos veces no duplica la factura")
    void repeatedInvocationIsIdempotent() {
        accounts.activate(tenantA, MockElectronicInvoicingProvider.ID, ProviderEnvironment.SIMULATION, null);
        Invoice invoice = storedFinalizedInvoice(tenantA);
        SubmitInvoiceService service = service(mock);

        service.submit(tenantA, invoice.id());
        service.submit(tenantA, invoice.id());

        assertEquals(1, mock.documentsCreated());
        assertEquals(1, mock.submitCalls(), "una factura en estado final no vuelve a enviarse");
    }

    @Test
    @DisplayName("timeout tras recepción: el reintento usa la misma clave y el proveedor no duplica")
    void timeoutThenRetryDoesNotDuplicate() {
        accounts.activate(tenantA, MockElectronicInvoicingProvider.ID, ProviderEnvironment.SIMULATION, null);
        Invoice invoice = storedFinalizedInvoice(tenantA);
        SubmitInvoiceService service = service(mock);

        mockBehavior.set(Behavior.TIMEOUT_AFTER_RECEIPT);
        Invoice afterTimeout = service.submit(tenantA, invoice.id());
        assertEquals(InvoiceStatus.SUBMITTED, afterTimeout.status());
        assertTrue(afterTimeout.outcomeUncertain());

        mockBehavior.set(Behavior.ACCEPT);
        Invoice afterRetry = service.submit(tenantA, invoice.id());
        assertEquals(InvoiceStatus.ACCEPTED, afterRetry.status());
        assertEquals(1, mock.documentsCreated(), "el proveedor recibió dos llamadas pero creó un solo documento");
        assertEquals(2, mock.submitCalls());
    }

    @Test
    @DisplayName("resultado incierto con proveedor sin idempotencia ni búsqueda: exige reconciliación, no reenvía")
    void uncertainWithoutDeduplicationRequiresReconciliation() {
        ScriptedProvider plain = new ScriptedProvider("plain", false, false, INVOICES);
        plain.submitResults.add(new SubmissionResult.TransientFailure("read timeout", true));
        accounts.activate(tenantA, plain.id, ProviderEnvironment.HABILITATION, null);
        Invoice invoice = storedFinalizedInvoice(tenantA);
        SubmitInvoiceService service = service(plain);

        service.submit(tenantA, invoice.id());
        DomainException e = assertThrows(DomainException.class, () -> service.submit(tenantA, invoice.id()));

        assertEquals("RECONCILIATION_REQUIRED", e.code());
        assertEquals(1, plain.submitted.size(), "nunca se reenvía a ciegas");
    }

    @Test
    @DisplayName("resultado incierto con búsqueda por número: encuentra el documento y no lo reenvía")
    void uncertainWithLookupFindsExistingDocument() {
        ScriptedProvider lookup = new ScriptedProvider("lookup", false, true, INVOICES);
        lookup.submitResults.add(new SubmissionResult.TransientFailure("read timeout", true));
        lookup.lookupResults.add(Optional.of(new SubmissionResult.Accepted(new ExternalReference("found-1"),
                new ValidationEvidence("CUFE-1", ProviderEnvironment.HABILITATION, InvoiceFixtures.NOW))));
        accounts.activate(tenantA, lookup.id, ProviderEnvironment.HABILITATION, null);
        Invoice invoice = storedFinalizedInvoice(tenantA);
        SubmitInvoiceService service = service(lookup);

        service.submit(tenantA, invoice.id());
        Invoice result = service.submit(tenantA, invoice.id());

        assertEquals(InvoiceStatus.ACCEPTED, result.status());
        assertEquals(1, lookup.submitted.size());
        assertEquals(1, lookup.lookupCalls);
        assertFalse(result.isLegallyValid(), "habilitación no tiene efectos tributarios");
    }

    @Test
    @DisplayName("proveedor caído: reintenta y tras el máximo de intentos marca la factura como fallida")
    void unavailableProviderEventuallyFails() {
        accounts.activate(tenantA, MockElectronicInvoicingProvider.ID, ProviderEnvironment.SIMULATION, null);
        mockBehavior.set(Behavior.UNAVAILABLE);
        Invoice invoice = storedFinalizedInvoice(tenantA);
        SubmitInvoiceService service = service(mock);

        assertEquals(InvoiceStatus.PENDING_SUBMISSION, service.submit(tenantA, invoice.id()).status());
        assertEquals(InvoiceStatus.PENDING_SUBMISSION, service.submit(tenantA, invoice.id()).status());
        Invoice last = service.submit(tenantA, invoice.id());

        assertEquals(InvoiceStatus.FAILED, last.status());
        assertEquals(0, mock.documentsCreated());
    }

    @Test
    @DisplayName("documento pendiente en el proveedor: el siguiente ciclo consulta estado en vez de reenviar")
    void pendingIsPolledNotResubmitted() {
        ScriptedProvider provider = new ScriptedProvider("alpha", true, false, INVOICES);
        accounts.activate(tenantA, provider.id, ProviderEnvironment.HABILITATION, null);
        Invoice invoice = storedFinalizedInvoice(tenantA);
        SubmitInvoiceService service = service(provider);

        assertEquals(InvoiceStatus.SUBMITTED, service.submit(tenantA, invoice.id()).status());
        service.submit(tenantA, invoice.id());

        assertEquals(1, provider.submitted.size());
        assertEquals(1, provider.queryCalls);
    }

    @Test
    @DisplayName("aislamiento: un tenant no puede transmitir la factura de otro")
    void cannotSubmitAnotherTenantsInvoice() {
        accounts.activate(tenantA, MockElectronicInvoicingProvider.ID, ProviderEnvironment.SIMULATION, null);
        accounts.activate(tenantB, MockElectronicInvoicingProvider.ID, ProviderEnvironment.SIMULATION, null);
        Invoice invoiceOfA = storedFinalizedInvoice(tenantA);

        DomainException e = assertThrows(DomainException.class, () -> service(mock).submit(tenantB, invoiceOfA.id()));

        assertEquals("INVOICE_NOT_FOUND", e.code(), "mismo error que un id inexistente: no revela existencia");
        assertEquals(0, mock.submitCalls());
    }

    @Test
    @DisplayName("cambio de proveedor por configuración: lo nuevo va al nuevo, lo pendiente sigue con el original")
    void providerSwitchKeepsInFlightDocumentsOnOriginalProvider() {
        ScriptedProvider alpha = new ScriptedProvider("alpha", true, false, INVOICES);
        ScriptedProvider beta = new ScriptedProvider("beta", true, false, INVOICES);
        SubmitInvoiceService service = service(alpha, beta);

        accounts.activate(tenantA, alpha.id, ProviderEnvironment.HABILITATION, null);
        Invoice inFlight = storedFinalizedInvoice(tenantA);
        service.submit(tenantA, inFlight.id());               // queda SUBMITTED en alpha

        accounts.activate(tenantA, beta.id, ProviderEnvironment.HABILITATION, null); // el tenant cambia a beta
        Invoice fresh = storedFinalizedInvoice(tenantA);
        service.submit(tenantA, fresh.id());
        service.submit(tenantA, inFlight.id());               // continúa en alpha

        assertEquals(1, alpha.submitted.size());
        assertEquals(1, alpha.queryCalls, "la factura en curso se consulta en el proveedor original");
        assertEquals(1, beta.submitted.size());
        assertEquals(fresh.id(), beta.submitted.getFirst().documentId());
    }

    @Test
    @DisplayName("no se simulan capacidades: proveedor sin soporte o no desplegado produce error explícito")
    void capabilityAndAvailabilityChecks() {
        ScriptedProvider notesOnly = new ScriptedProvider("notesonly", true, false, Set.of(DocumentKind.CREDIT_NOTE));
        accounts.activate(tenantA, notesOnly.id, ProviderEnvironment.HABILITATION, null);
        Invoice invoice = storedFinalizedInvoice(tenantA);

        assertEquals("PROVIDER_CAPABILITY_UNSUPPORTED",
                assertThrows(DomainException.class, () -> service(notesOnly).submit(tenantA, invoice.id())).code());
        assertEquals("PROVIDER_NOT_AVAILABLE",
                assertThrows(DomainException.class, () -> service(mock).submit(tenantA, invoice.id())).code());

        accounts.activate(tenantA, MockElectronicInvoicingProvider.ID, ProviderEnvironment.PRODUCTION, null);
        assertEquals("PROVIDER_ENVIRONMENT_UNSUPPORTED",
                assertThrows(DomainException.class, () -> service(mock).submit(tenantA, invoice.id())).code(),
                "el adaptador simulado jamás opera en producción");
    }

    @Test
    @DisplayName("sin proveedor configurado o con borrador, el envío se rechaza")
    void preconditions() {
        Invoice invoice = storedFinalizedInvoice(tenantA);
        assertEquals("NO_PROVIDER_CONFIGURED",
                assertThrows(DomainException.class, () -> service(mock).submit(tenantA, invoice.id())).code());

        Invoice draft = InvoiceFixtures.draft(tenantA);
        invoices.save(draft);
        assertEquals("INVALID_STATE_TRANSITION",
                assertThrows(DomainException.class, () -> service(mock).submit(tenantA, draft.id())).code());
    }

    @Test
    @DisplayName("una excepción inesperada del adaptador se trata como resultado incierto, no como éxito ni fallo")
    void adapterExceptionIsUncertain() {
        ScriptedProvider flaky = new ScriptedProvider("flaky", true, false, INVOICES);
        flaky.throwOnSubmit = new IllegalStateException("socket closed");
        accounts.activate(tenantA, flaky.id, ProviderEnvironment.HABILITATION, null);
        Invoice invoice = storedFinalizedInvoice(tenantA);

        Invoice result = service(flaky).submit(tenantA, invoice.id());

        assertEquals(InvoiceStatus.SUBMITTED, result.status());
        assertTrue(result.outcomeUncertain());
    }
}
