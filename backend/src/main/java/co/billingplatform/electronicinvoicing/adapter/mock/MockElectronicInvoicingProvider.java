package co.billingplatform.electronicinvoicing.adapter.mock;

import co.billingplatform.electronicinvoicing.adapter.common.HmacWebhookVerifier;
import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ElectronicDocument;
import co.billingplatform.electronicinvoicing.domain.ExternalReference;
import co.billingplatform.electronicinvoicing.domain.IdempotencyKey;
import co.billingplatform.electronicinvoicing.domain.InboundWebhook;
import co.billingplatform.electronicinvoicing.domain.ProviderAccount;
import co.billingplatform.electronicinvoicing.domain.ProviderCapabilities;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.electronicinvoicing.domain.ProviderEvent;
import co.billingplatform.electronicinvoicing.domain.ProviderId;
import co.billingplatform.electronicinvoicing.domain.SubmissionResult;
import co.billingplatform.electronicinvoicing.domain.ValidationEvidence;
import co.billingplatform.electronicinvoicing.domain.WebhookVerificationException;
import co.billingplatform.electronicinvoicing.port.ElectronicInvoicingProvider;
import co.billingplatform.electronicinvoicing.port.SecretResolver;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Proveedor simulado para desarrollo y pruebas del contrato común.
 *
 * <p>Garantías de honestidad: solo opera en {@link ProviderEnvironment#SIMULATION}; sus
 * claves de documento empiezan por {@value #SIMULATED_KEY_PREFIX}; nunca contacta a la DIAN
 * ni imita respuestas reales de la DIAN. Un documento "validado" por este adaptador no
 * tiene ningún efecto tributario.
 */
public final class MockElectronicInvoicingProvider implements ElectronicInvoicingProvider {

    public static final ProviderId ID = new ProviderId("mock");
    public static final String SIMULATED_KEY_PREFIX = "SIMULATED-";
    static final String SIGNATURE_HEADER = "X-Mock-Signature";
    static final String TIMESTAMP_HEADER = "X-Mock-Timestamp";

    /** Comportamiento simulado, decidido por documento. */
    public enum Behavior {
        ACCEPT,
        PENDING,
        REJECT,
        /** El proveedor recibe el documento pero la respuesta se pierde (timeout). */
        TIMEOUT_AFTER_RECEIPT,
        /** El proveedor no está disponible; nada se recibe. */
        UNAVAILABLE
    }

    private record Stored(ExternalReference reference, String fullNumber, SubmissionResult finalResult) {}

    private final SecretResolver secrets;
    private final Clock clock;
    private final Function<ElectronicDocument, Behavior> behavior;
    private final HmacWebhookVerifier webhookVerifier =
            new HmacWebhookVerifier(SIGNATURE_HEADER, TIMESTAMP_HEADER, Duration.ofMinutes(5));
    private final Map<String, Stored> byIdempotencyKey = new ConcurrentHashMap<>();
    private final Map<String, Stored> byNumber = new ConcurrentHashMap<>();
    private final Map<String, Stored> byReference = new ConcurrentHashMap<>();
    private final AtomicInteger documentsCreated = new AtomicInteger();
    private final AtomicInteger submitCalls = new AtomicInteger();

    public MockElectronicInvoicingProvider(SecretResolver secrets, Clock clock, Function<ElectronicDocument, Behavior> behavior) {
        this.secrets = Objects.requireNonNull(secrets);
        this.clock = Objects.requireNonNull(clock);
        this.behavior = Objects.requireNonNull(behavior);
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                Set.of(DocumentKind.INVOICE, DocumentKind.POS_EQUIVALENT, DocumentKind.CREDIT_NOTE),
                Set.of(ProviderEnvironment.SIMULATION),
                true, true, true, true);
    }

    @Override
    public synchronized SubmissionResult submit(ProviderAccount account, ElectronicDocument document, IdempotencyKey key) {
        requireSimulation(account);
        submitCalls.incrementAndGet();
        String scopedKey = account.tenantId() + ":" + key.value();
        Stored existing = byIdempotencyKey.get(scopedKey);
        if (existing != null) {
            return existing.finalResult();
        }

        Behavior b = behavior.apply(document);
        if (b == Behavior.UNAVAILABLE) {
            return new SubmissionResult.TransientFailure("mock provider unavailable", false);
        }

        ExternalReference reference = new ExternalReference("mock-" + UUID.randomUUID());
        SubmissionResult result = switch (b) {
            case ACCEPT, TIMEOUT_AFTER_RECEIPT -> new SubmissionResult.Accepted(reference, evidence(document));
            case PENDING -> new SubmissionResult.Pending(reference);
            case REJECT -> new SubmissionResult.Rejected(reference, List.of("Simulated rejection"));
            case UNAVAILABLE -> throw new IllegalStateException("unreachable");
        };
        Stored stored = new Stored(reference, scopedNumber(account, document.fullNumber()), result);
        byIdempotencyKey.put(scopedKey, stored);
        byNumber.put(stored.fullNumber(), stored);
        byReference.put(reference.value(), stored);
        documentsCreated.incrementAndGet();

        return b == Behavior.TIMEOUT_AFTER_RECEIPT
                ? new SubmissionResult.TransientFailure("read timeout", true)
                : result;
    }

    @Override
    public SubmissionResult queryStatus(ProviderAccount account, ExternalReference reference) {
        requireSimulation(account);
        Stored stored = byReference.get(reference.value());
        if (stored == null || !stored.fullNumber().startsWith(account.tenantId() + ":")) {
            return new SubmissionResult.PermanentFailure("unknown reference");
        }
        return stored.finalResult();
    }

    @Override
    public Optional<SubmissionResult> findByDocumentNumber(ProviderAccount account, String fullDocumentNumber) {
        requireSimulation(account);
        return Optional.ofNullable(byNumber.get(scopedNumber(account, fullDocumentNumber))).map(Stored::finalResult);
    }

    /**
     * Formato del cuerpo (texto, una clave por línea): {@code eventId}, {@code reference},
     * {@code outcome} (ACCEPTED|REJECTED), {@code documentKey}, {@code reason}.
     */
    @Override
    public ProviderEvent parseWebhook(ProviderAccount account, InboundWebhook webhook) {
        requireSimulation(account);
        webhookVerifier.verify(webhook, secrets.resolve(account.webhookSecretRef()));

        Map<String, String> fields = new HashMap<>();
        for (String line : new String(webhook.body(), StandardCharsets.UTF_8).split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                fields.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        String eventId = fields.get("eventId");
        String reference = fields.get("reference");
        String outcome = fields.get("outcome");
        if (eventId == null || reference == null || outcome == null) {
            throw new WebhookVerificationException("malformed mock webhook payload");
        }
        ExternalReference ref = new ExternalReference(reference);
        SubmissionResult result = switch (outcome) {
            case "ACCEPTED" -> new SubmissionResult.Accepted(ref, new ValidationEvidence(
                    SIMULATED_KEY_PREFIX + fields.getOrDefault("documentKey", UUID.randomUUID().toString()),
                    ProviderEnvironment.SIMULATION, clock.instant()));
            case "REJECTED" -> new SubmissionResult.Rejected(ref, List.of(fields.getOrDefault("reason", "Simulated rejection")));
            default -> throw new WebhookVerificationException("unknown outcome");
        };
        return new ProviderEvent(eventId, ref, result);
    }

    public int documentsCreated() {
        return documentsCreated.get();
    }

    public int submitCalls() {
        return submitCalls.get();
    }

    private ValidationEvidence evidence(ElectronicDocument document) {
        return new ValidationEvidence(
                SIMULATED_KEY_PREFIX + UUID.nameUUIDFromBytes(
                        (document.tenantId() + ":" + document.fullNumber()).getBytes(StandardCharsets.UTF_8)),
                ProviderEnvironment.SIMULATION,
                clock.instant());
    }

    private static String scopedNumber(ProviderAccount account, String fullNumber) {
        return account.tenantId() + ":" + fullNumber;
    }

    private static void requireSimulation(ProviderAccount account) {
        if (account.environment() != ProviderEnvironment.SIMULATION || !ID.equals(account.providerId())) {
            throw new IllegalStateException("Mock provider only operates in SIMULATION");
        }
    }
}
