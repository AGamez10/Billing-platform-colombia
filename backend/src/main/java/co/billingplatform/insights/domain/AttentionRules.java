package co.billingplatform.insights.domain;

import co.billingplatform.insights.domain.Attention.Severity;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reglas puras: de los hechos de una organización a la lista de pendientes, ordenada por
 * gravedad. Sin base de datos ni reloj propio: se prueban con datos fijos.
 */
public final class AttentionRules {

    public static final int RANGE_EXPIRY_WARNING_DAYS = 30;
    public static final int RANGE_EXPIRY_CRITICAL_DAYS = 7;
    public static final int SUBSCRIPTION_ENDING_DAYS = 7;

    public record RangeFact(String kind, String prefix, long size, long remaining, LocalDate validTo) {}

    /**
     * @param activeEnvironment ambiente de la cuenta de proveedor activa, {@code null} si no hay
     * @param quotaLimit        {@code null} si no aplica o el usuario no puede ver el plan
     * @param subscriptionStatus {@code null} si el usuario no puede ver el plan
     */
    public record Facts(String activeEnvironment, boolean issuerReady, List<RangeFact> ranges,
                        Integer quotaLimit, int quotaUsed, boolean trial,
                        String subscriptionStatus, LocalDate periodEnd,
                        int rejected, int failed, int uncertain, int staleDrafts) {}

    private AttentionRules() {}

    public static List<Attention> evaluate(Facts f, LocalDate today) {
        List<Attention> out = new ArrayList<>();
        boolean real = f.activeEnvironment() != null && !"SIMULATION".equals(f.activeEnvironment());

        if ("PAST_DUE".equals(f.subscriptionStatus())) {
            out.add(new Attention("SUBSCRIPTION_PAST_DUE", Severity.CRITICAL, "Tu plan está vencido",
                    "No puedes emitir documentos hasta renovarlo.", "Renovar plan", "/plan", "SUBSCRIPTION_PAST_DUE:" + f.periodEnd()));
        } else if ("ACTIVE".equals(f.subscriptionStatus()) && f.periodEnd() != null) {
            long days = ChronoUnit.DAYS.between(today, f.periodEnd());
            if (days >= 0 && days <= SUBSCRIPTION_ENDING_DAYS) {
                out.add(new Attention("SUBSCRIPTION_ENDING", Severity.WARNING, "Tu plan vence " + when(days),
                        "Renuévalo para no interrumpir la facturación.", "Renovar plan", "/plan", "SUBSCRIPTION_ENDING:" + f.periodEnd()));
            }
        }

        if (f.activeEnvironment() == null) {
            out.add(new Attention("PROVIDER_MISSING", Severity.INFO, "Conecta la facturación electrónica",
                    "Empieza en modo práctica: no gasta documentos ni va a la DIAN.", "Configurar", "/empezar", "PROVIDER_MISSING"));
        } else {
            if (f.ranges().stream().noneMatch(r -> r.remaining() > 0 && !r.validTo().isBefore(today))) {
                out.add(new Attention("NUMBERING_MISSING", Severity.CRITICAL, "No tienes numeración vigente",
                        "Sin una resolución vigente no se puede emitir.", "Registrar resolución", "/configuracion",
                        "NUMBERING_MISSING:" + f.activeEnvironment()));
            }
            for (RangeFact r : f.ranges()) {
                if (r.remaining() <= 0 || r.validTo().isBefore(today)) continue;
                long days = ChronoUnit.DAYS.between(today, r.validTo());
                String name = label(r);
                if (days <= RANGE_EXPIRY_WARNING_DAYS) {
                    out.add(new Attention("NUMBERING_EXPIRING", days <= RANGE_EXPIRY_CRITICAL_DAYS ? Severity.CRITICAL : Severity.WARNING,
                            "La numeración " + name + " vence " + when(days),
                            "Pide a la DIAN una nueva resolución y regístrala antes de que venza.", "Ver numeración", "/configuracion",
                            "NUMBERING_EXPIRING:" + r.prefix() + ":" + r.validTo() + ":" + (days <= RANGE_EXPIRY_CRITICAL_DAYS)));
                }
                long threshold = Math.max(10, r.size() / 10);
                if (r.remaining() <= threshold) {
                    out.add(new Attention("NUMBERING_LOW", r.remaining() <= Math.max(3, threshold / 4) ? Severity.CRITICAL : Severity.WARNING,
                            "Quedan " + r.remaining() + " números " + name,
                            "Solicita una nueva resolución antes de que se agote.", "Ver numeración", "/configuracion",
                            "NUMBERING_LOW:" + r.prefix() + ":" + r.size() + ":" + (r.remaining() <= Math.max(3, threshold / 4))));
                }
            }
            if (real && !f.issuerReady()) {
                out.add(new Attention("ISSUER_PROFILE_INCOMPLETE", Severity.CRITICAL, "Completa los datos de tu empresa",
                        "Sin dirección y datos tributarios no se puede emitir en " + envLabel(f.activeEnvironment()) + ".",
                        "Completar", "/empresa", "ISSUER_PROFILE_INCOMPLETE"));
            }
        }
        if (!real && f.activeEnvironment() != null && !f.issuerReady()) {
            out.add(new Attention("ISSUER_PROFILE_INCOMPLETE", Severity.INFO, "Completa los datos de tu empresa",
                    "No hacen falta para practicar, sí para facturar en real.", "Completar", "/empresa", "ISSUER_PROFILE_INCOMPLETE"));
        }

        if (f.quotaLimit() != null && f.quotaLimit() > 0) {
            if (f.quotaUsed() >= f.quotaLimit()) {
                out.add(new Attention("QUOTA_REACHED", Severity.CRITICAL,
                        f.trial() ? "Usaste todos los documentos de la prueba" : "Llegaste al límite de tu plan",
                        "Mejora tu plan para seguir emitiendo.", "Ver planes", "/plan", "QUOTA_REACHED:" + f.quotaLimit()));
            } else if (f.quotaUsed() * 10 >= f.quotaLimit() * 8) {
                out.add(new Attention("QUOTA_HIGH", Severity.WARNING,
                        "Has usado " + f.quotaUsed() + " de " + f.quotaLimit() + " documentos",
                        f.trial() ? "Elige un plan antes de que se acabe la prueba." : "Considera mejorar tu plan.", "Ver planes", "/plan",
                        "QUOTA_HIGH:" + f.quotaLimit()));
            }
        }

        if (f.rejected() > 0) {
            out.add(new Attention("DOCUMENTS_REJECTED", Severity.CRITICAL,
                    f.rejected() == 1 ? "1 documento fue rechazado" : f.rejected() + " documentos fueron rechazados",
                    "Revisa el motivo, corrige el dato y emite uno nuevo.", "Revisar", "/facturas?estado=REJECTED",
                    "DOCUMENTS_REJECTED:" + f.rejected()));
        }
        if (f.failed() > 0) {
            out.add(new Attention("DOCUMENTS_FAILED", Severity.CRITICAL,
                    f.failed() == 1 ? "1 documento no se pudo enviar" : f.failed() + " documentos no se pudieron enviar",
                    "Revisa el detalle de cada uno.", "Revisar", "/facturas?estado=FAILED", "DOCUMENTS_FAILED:" + f.failed()));
        }
        if (f.uncertain() > 0) {
            out.add(new Attention("DOCUMENTS_UNCERTAIN", Severity.WARNING,
                    f.uncertain() == 1 ? "1 documento espera confirmación" : f.uncertain() + " documentos esperan confirmación",
                    "Los seguimos consultando con el proveedor; no los emitas de nuevo.", "Ver", "/facturas",
                    "DOCUMENTS_UNCERTAIN:" + f.uncertain()));
        }
        if (f.staleDrafts() > 0) {
            out.add(new Attention("DRAFTS_PENDING", Severity.INFO,
                    f.staleDrafts() == 1 ? "Tienes 1 borrador sin emitir" : "Tienes " + f.staleDrafts() + " borradores sin emitir",
                    "Emítelos o bórralos para no confundirte.", "Ver borradores", "/facturas?estado=DRAFT", "DRAFTS_PENDING"));
        }
        out.sort(Comparator.comparing((Attention a) -> a.severity()).reversed());
        return List.copyOf(out);
    }

    private static String label(RangeFact r) {
        String kind = switch (r.kind()) {
            case "POS_EQUIVALENT" -> "del POS";
            case "CREDIT_NOTE" -> "de notas crédito";
            default -> "de facturas";
        };
        return kind + (r.prefix().isEmpty() ? "" : " (" + r.prefix() + ")");
    }

    private static String when(long days) {
        return days == 0 ? "hoy" : days == 1 ? "mañana" : "en " + days + " días";
    }

    private static String envLabel(String env) {
        return "PRODUCTION".equals(env) ? "producción" : "habilitación";
    }
}
