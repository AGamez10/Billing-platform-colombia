package co.billingplatform.insights.application;

import co.billingplatform.insights.domain.Attention;
import co.billingplatform.shared.application.EmailHtml;
import co.billingplatform.shared.application.Mailer;
import co.billingplatform.shared.domain.TenantId;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Avisos automáticos por correo a dueños y administradores: un solo correo con lo nuevo e
 * importante (urgente o pronto), cada pendiente una sola vez por su clave. Lo informativo se
 * queda en el inicio para no llenar el buzón.
 */
public final class AlertNotifier {

    /** Pendientes que merecen correo; los demás solo se ven en el inicio. */
    public static final Set<String> EMAILED = Set.of(
            "SUBSCRIPTION_PAST_DUE", "SUBSCRIPTION_ENDING", "NUMBERING_MISSING", "NUMBERING_EXPIRING", "NUMBERING_LOW",
            "ISSUER_PROFILE_INCOMPLETE", "QUOTA_HIGH", "QUOTA_REACHED", "DOCUMENTS_REJECTED", "DOCUMENTS_FAILED");

    public interface Ledger {
        Set<String> alreadySent(TenantId tenantId, List<String> keys);

        void record(TenantId tenantId, Attention attention, int recipients);

        /** Correos de los dueños y administradores activos. */
        List<String> recipients(TenantId tenantId);

        String organizationName(TenantId tenantId);
    }

    private final InsightsService insights;
    private final Ledger ledger;
    private final Mailer mailer;
    private final String brand;
    private final String baseUrl;

    public AlertNotifier(InsightsService insights, Ledger ledger, Mailer mailer, String brand, String baseUrl) {
        this.insights = Objects.requireNonNull(insights);
        this.ledger = Objects.requireNonNull(ledger);
        this.mailer = Objects.requireNonNull(mailer);
        this.brand = Objects.requireNonNull(brand);
        String url = Objects.requireNonNull(baseUrl).strip();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Dentro del tenant. @return cuántos pendientes se avisaron */
    public int notify(TenantId tenantId) {
        List<Attention> candidates = insights.attention(tenantId, true).stream()
                .filter(a -> a.severity() != Attention.Severity.INFO && EMAILED.contains(a.code()))
                .toList();
        if (candidates.isEmpty()) {
            return 0;
        }
        Set<String> sent = ledger.alreadySent(tenantId, candidates.stream().map(Attention::key).toList());
        List<Attention> fresh = candidates.stream().filter(a -> !sent.contains(a.key())).toList();
        if (fresh.isEmpty()) {
            return 0;
        }
        List<String> to = ledger.recipients(tenantId);
        for (String recipient : to) {
            mailer.send(email(ledger.organizationName(tenantId), recipient, fresh));
        }
        for (Attention a : fresh) {
            ledger.record(tenantId, a, to.size());
        }
        return fresh.size();
    }

    Mailer.Email email(String organization, String to, List<Attention> items) {
        String org = EmailHtml.oneLine(organization);
        String subject = (items.size() == 1 ? items.getFirst().title() : items.size() + " asuntos requieren tu atención") + " · " + org;
        StringBuilder text = new StringBuilder("Hola,\n\nEsto requiere tu atención en ").append(org).append(":\n\n");
        StringBuilder html = new StringBuilder("<p>Esto requiere tu atención en <strong>").append(EmailHtml.esc(org)).append("</strong>:</p><ul>");
        for (Attention a : items) {
            String link = baseUrl + a.actionPath();
            text.append("• ").append(a.title()).append(". ").append(a.detail()).append("\n  ").append(a.actionLabel()).append(": ")
                    .append(link).append("\n\n");
            html.append("<li style=\"margin-bottom:12px\"><strong>").append(EmailHtml.esc(a.title())).append("</strong><br>")
                    .append(EmailHtml.esc(a.detail())).append("<br><a href=\"").append(EmailHtml.esc(link)).append("\">")
                    .append(EmailHtml.esc(a.actionLabel())).append("</a></li>");
        }
        html.append("</ul><p class=\"muted\">Recibes estos avisos por ser dueño o administrador. Puedes apagarlos en Ajustes → Datos de tu empresa.</p>");
        text.append("Recibes estos avisos por ser dueño o administrador. Puedes apagarlos en Ajustes → Datos de tu empresa.\n\n").append(brand);
        return new Mailer.Email(to, subject, text.toString(), EmailHtml.layout(brand, "Requiere tu atención", html.toString()));
    }
}
