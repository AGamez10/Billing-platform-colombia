package co.billingplatform.invoicing.application;

import co.billingplatform.electronicinvoicing.domain.DocumentKind;
import co.billingplatform.electronicinvoicing.domain.ProviderEnvironment;
import co.billingplatform.invoicing.domain.Invoice;
import co.billingplatform.invoicing.domain.InvoiceStatus;
import co.billingplatform.shared.application.AuditTrail;
import co.billingplatform.shared.application.EmailHtml;
import co.billingplatform.shared.application.Mailer;
import co.billingplatform.shared.domain.Actor;
import co.billingplatform.shared.domain.DomainException;
import co.billingplatform.shared.domain.OpaqueTokens;
import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.TenantId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Compartir un documento validado con el comprador: enlace de solo lectura (para WhatsApp o
 * copiar) o correo con ese enlace. El enlace no requiere cuenta, vence y se puede revocar; solo
 * se guarda el hash del token.
 *
 * <p>No reemplaza la entrega del XML (AttachedDocument) que exige la DIAN para la factura
 * electrónica: eso lo hará el adaptador del proveedor cuando entregue el archivo (ADR-0027).
 */
public final class DocumentSharingService {

    public static final Duration LINK_VALIDITY = Duration.ofDays(180);
    public static final int MAX_LINKS_PER_DAY = 20;

    public enum Channel { EMAIL, LINK }

    public record ShareLink(TenantId tenantId, UUID id, UUID invoiceId, byte[] tokenHash, Channel channel, String sentTo,
                            UUID createdBy, Instant createdAt, Instant expiresAt) {}

    public record Target(TenantId tenantId, UUID invoiceId) {}

    public interface ShareLinks {
        void insert(ShareLink link);

        int countSince(TenantId tenantId, UUID invoiceId, Instant since);

        /** Sin tenant en contexto: solo enlaces vigentes de organizaciones activas. */
        Optional<Target> find(byte[] tokenHash);
    }

    /** @param link URL completa con el token en el fragmento; solo se entrega una vez */
    public record Shared(String link, Instant expiresAt, String sentTo) {}

    private final InvoiceRepository invoices;
    private final ShareLinks links;
    private final IssuerDirectory issuers;
    private final Mailer mailer;
    private final AuditTrail audit;
    private final String brand;
    private final String baseUrl;
    private final Clock clock;

    public DocumentSharingService(InvoiceRepository invoices, ShareLinks links, IssuerDirectory issuers, Mailer mailer,
                                  AuditTrail audit, String brand, String baseUrl, Clock clock) {
        this.invoices = Objects.requireNonNull(invoices);
        this.links = Objects.requireNonNull(links);
        this.issuers = Objects.requireNonNull(issuers);
        this.mailer = Objects.requireNonNull(mailer);
        this.audit = Objects.requireNonNull(audit);
        this.brand = Objects.requireNonNull(brand);
        String url = Objects.requireNonNull(baseUrl).strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.clock = Objects.requireNonNull(clock);
    }

    public Shared createLink(TenantId tenantId, UUID userId, UUID invoiceId) {
        Invoice invoice = shareable(tenantId, invoiceId);
        Shared shared = issue(tenantId, userId, invoice, Channel.LINK, null);
        audit.record(new AuditTrail.Event(tenantId, Actor.user(userId), "INVOICE_LINK_CREATED", "invoice",
                invoiceId.toString(), Map.of("expiresAt", shared.expiresAt().toString())));
        return shared;
    }

    /** @param overrideEmail otro destinatario (p. ej. el correo que el cliente dicta en caja) */
    public Shared sendByEmail(TenantId tenantId, UUID userId, UUID invoiceId, String overrideEmail) {
        Invoice invoice = shareable(tenantId, invoiceId);
        String to = overrideEmail == null || overrideEmail.isBlank() ? invoice.customer().email()
                : new Party(invoice.customer().identification(), "x", overrideEmail).email(); // misma validación de correo
        if (to == null) {
            throw new DomainException("CUSTOMER_EMAIL_REQUIRED", "The customer has no email; provide one");
        }
        Shared shared = issue(tenantId, userId, invoice, Channel.EMAIL, to);
        mailer.send(email(invoice, issuers.issuerOf(tenantId), to, shared.link()));
        audit.record(new AuditTrail.Event(tenantId, Actor.user(userId), "INVOICE_SENT", "invoice", invoiceId.toString(),
                Map.of("to", to, "overridden", String.valueOf(overrideEmail != null && !overrideEmail.isBlank()))));
        return shared;
    }

    public Optional<Target> resolve(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        return links.find(OpaqueTokens.sha256(token));
    }

    private Invoice shareable(TenantId tenantId, UUID invoiceId) {
        Invoice invoice = invoices.findById(tenantId, invoiceId)
                .orElseThrow(() -> new DomainException("INVOICE_NOT_FOUND", "Invoice not found"));
        if (invoice.status() != InvoiceStatus.ACCEPTED || invoice.evidence().isEmpty()) {
            throw new DomainException("INVOICE_NOT_VALIDATED", "Only validated documents can be shared");
        }
        return invoice;
    }

    private Shared issue(TenantId tenantId, UUID userId, Invoice invoice, Channel channel, String sentTo) {
        Instant now = clock.instant();
        if (links.countSince(tenantId, invoice.id(), now.minus(Duration.ofDays(1))) >= MAX_LINKS_PER_DAY) {
            throw new DomainException("SHARE_LIMIT_REACHED", "Too many links for this document today");
        }
        String token = OpaqueTokens.randomBase64Url(32);
        Instant expires = now.plus(LINK_VALIDITY);
        links.insert(new ShareLink(tenantId, UUID.randomUUID(), invoice.id(), OpaqueTokens.sha256(token), channel, sentTo,
                userId, now, expires));
        return new Shared(baseUrl + "/documento#t=" + token, expires, sentTo);
    }

    Mailer.Email email(Invoice invoice, Party issuer, String to, String link) {
        boolean practice = invoice.environment().orElseThrow() != ProviderEnvironment.PRODUCTION;
        String kind = invoice.kind() == DocumentKind.POS_EQUIVALENT ? "Documento equivalente POS" : "Factura electrónica";
        String number = invoice.number().orElseThrow().formatted();
        String total = cop(invoice.calculation().payableAmount());
        String issuerName = EmailHtml.oneLine(issuer.legalName());
        String warning = switch (invoice.environment().orElseThrow()) {
            case PRODUCTION -> "";
            case HABILITATION -> "Documento de pruebas de habilitación: no tiene validez tributaria.";
            case SIMULATION -> "Documento de práctica: no se envió a la DIAN y no tiene validez tributaria.";
        };
        String subject = (practice ? "[Sin validez tributaria] " : "") + kind + " " + number + " de " + issuerName;
        String text = """
                Hola,

                %s te envía su %s %s por %s.
                %s
                Puedes verlo, descargarlo o imprimirlo aquí (el enlace vence en %d días):

                %s

                Enviado con %s.
                """.formatted(issuerName, kind.toLowerCase(Locale.ROOT), number, total,
                warning.isEmpty() ? "" : "\n" + warning + "\n", LINK_VALIDITY.toDays(), link, brand);
        String html = EmailHtml.layout(brand, EmailHtml.esc(kind) + " " + EmailHtml.esc(number),
                "<p><strong>" + EmailHtml.esc(issuerName) + "</strong> te envía su " + EmailHtml.esc(kind.toLowerCase(Locale.ROOT))
                        + " <strong>" + EmailHtml.esc(number) + "</strong> por <strong>" + EmailHtml.esc(total) + "</strong>.</p>"
                        + (warning.isEmpty() ? "" : "<p style=\"background:#fff3d6;color:#7d5200;padding:10px 12px;border-radius:8px\">"
                        + EmailHtml.esc(warning) + "</p>")
                        + EmailHtml.button(link, "Ver documento")
                        + "<p class=\"muted\">El enlace vence en " + LINK_VALIDITY.toDays() + " días.</p>");
        return new Mailer.Email(to, subject, text, html);
    }

    static String cop(BigDecimal amount) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CO"));
        f.setMaximumFractionDigits(amount.stripTrailingZeros().scale() > 0 ? 2 : 0);
        return f.format(amount.setScale(2, RoundingMode.HALF_UP)).replace(' ', ' ');
    }
}
