package co.billingplatform.insights.application;

import co.billingplatform.insights.domain.Attention;
import co.billingplatform.insights.domain.AttentionRules;
import co.billingplatform.shared.application.Mailer;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AlertNotifierTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final Set<String> ledgerKeys = new HashSet<>();
    private final List<Mailer.Email> sent = new ArrayList<>();
    private AttentionRules.Facts facts;

    private final InsightsService.FactsReader reader = new InsightsService.FactsReader() {
        @Override public AttentionRules.Facts facts(TenantId t, LocalDate today, boolean plan) { return facts; }
        @Override public List<InsightsService.DailyTotal> dailySales(TenantId t, String e, LocalDate f, LocalDate to) { return List.of(); }
        @Override public int acceptedSalesDocuments(TenantId t, String e, LocalDate f, LocalDate to) { return 0; }
        @Override public BigDecimal credited(TenantId t, String e, LocalDate f, LocalDate to) { return BigDecimal.ZERO; }
    };

    private final AlertNotifier.Ledger ledger = new AlertNotifier.Ledger() {
        @Override public Set<String> alreadySent(TenantId t, List<String> keys) {
            Set<String> out = new HashSet<>(keys);
            out.retainAll(ledgerKeys);
            return out;
        }
        @Override public void record(TenantId t, Attention a, int recipients) { ledgerKeys.add(a.key()); }
        @Override public List<String> recipients(TenantId t) { return List.of("dueno@example.com", "admin@example.com"); }
        @Override public String organizationName(TenantId t) { return "Hogar <Patitas>"; }
    };

    private AlertNotifier notifier() {
        var insights = new InsightsService(reader, Clock.fixed(Instant.parse("2026-11-19T15:00:00Z"), ZoneOffset.UTC));
        return new AlertNotifier(insights, ledger, sent::add, "Marca", "https://app.example.co/");
    }

    @Test
    @DisplayName("un correo por destinatario con lo nuevo e importante; lo informativo no; nada se repite")
    void notifiesOnce() {
        LocalDate today = LocalDate.of(2026, 11, 19);
        facts = new AttentionRules.Facts("PRODUCTION", true,
                List.of(new AttentionRules.RangeFact("INVOICE", "FV", 1000, 900, today.plusDays(10))),
                null, 0, false, null, null, 1, 0, 0, 3, Instant.parse("2026-11-19T10:00:00Z"), null);
        assertEquals(2, notifier().notify(tenant), "rechazado + numeración; los borradores no van por correo");
        assertEquals(2, sent.size());
        Mailer.Email mail = sent.getFirst();
        assertEquals("2 asuntos requieren tu atención · Hogar <Patitas>", mail.subject());
        assertTrue(mail.html().contains("Hogar &lt;Patitas&gt;"), "nombre escapado en HTML");
        assertTrue(mail.text().contains("https://app.example.co/facturas?estado=REJECTED"));

        assertEquals(0, notifier().notify(tenant));
        assertEquals(2, sent.size());

        facts = new AttentionRules.Facts("PRODUCTION", true,
                List.of(new AttentionRules.RangeFact("INVOICE", "FV", 1000, 900, today.plusDays(10))),
                null, 0, false, null, null, 2, 0, 0, 3, Instant.parse("2026-11-19T14:00:00Z"), null);
        assertEquals(1, notifier().notify(tenant), "un rechazo nuevo sí se avisa");
        assertTrue(sent.getLast().subject().startsWith("2 documentos fueron rechazados"));
    }

    @Test
    @DisplayName("en práctica, los datos de la empresa incompletos no generan correo")
    void practiceIsQuiet() {
        facts = new AttentionRules.Facts("SIMULATION", false,
                List.of(new AttentionRules.RangeFact("INVOICE", "P", 5000, 5000, LocalDate.of(2027, 11, 19))),
                null, 0, false, null, null, 0, 0, 0, 0);
        assertEquals(0, notifier().notify(tenant));
        assertTrue(sent.isEmpty());
    }
}
