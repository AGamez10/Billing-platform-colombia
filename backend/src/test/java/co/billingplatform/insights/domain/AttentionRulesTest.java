package co.billingplatform.insights.domain;

import co.billingplatform.insights.domain.Attention.Severity;
import co.billingplatform.insights.domain.AttentionRules.Facts;
import co.billingplatform.insights.domain.AttentionRules.RangeFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AttentionRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);
    private static final RangeFact HEALTHY = new RangeFact("INVOICE", "FV", 1000, 900, TODAY.plusYears(1));

    private static Facts facts(String env, boolean ready, List<RangeFact> ranges) {
        return new Facts(env, ready, ranges, null, 0, false, null, null, 0, 0, 0, 0);
    }

    private static List<String> codes(Facts f) {
        return AttentionRules.evaluate(f, TODAY).stream().map(Attention::code).toList();
    }

    @Test
    @DisplayName("sin proveedor: conectar; en práctica el perfil es informativo; en real es crítico")
    void setup() {
        assertEquals(List.of("PROVIDER_MISSING"), codes(facts(null, false, List.of())));
        List<Attention> practice = AttentionRules.evaluate(facts("SIMULATION", false, List.of(HEALTHY)), TODAY);
        assertEquals(List.of("ISSUER_PROFILE_INCOMPLETE"), practice.stream().map(Attention::code).toList());
        assertEquals(Severity.INFO, practice.getFirst().severity());
        List<Attention> real = AttentionRules.evaluate(facts("PRODUCTION", false, List.of(HEALTHY)), TODAY);
        assertEquals(Severity.CRITICAL, real.getFirst().severity());
        assertTrue(real.getFirst().detail().contains("producción"));
        assertEquals(List.of(), codes(facts("PRODUCTION", true, List.of(HEALTHY))));
    }

    @Test
    @DisplayName("numeración: falta, vence en 30 o 7 días, o quedan pocos números; vencida o agotada no cuenta")
    void numbering() {
        assertEquals(List.of("NUMBERING_MISSING"), codes(facts("PRODUCTION", true, List.of())));
        assertEquals(List.of("NUMBERING_MISSING"), codes(facts("PRODUCTION", true,
                List.of(new RangeFact("INVOICE", "FV", 100, 0, TODAY.plusYears(1)), new RangeFact("INVOICE", "FV", 100, 50, TODAY.minusDays(1))))));
        List<Attention> expiring = AttentionRules.evaluate(facts("PRODUCTION", true,
                List.of(new RangeFact("INVOICE", "FV", 1000, 900, TODAY.plusDays(20)))), TODAY);
        assertEquals(Severity.WARNING, expiring.getFirst().severity());
        assertEquals("La numeración de facturas (FV) vence en 20 días", expiring.getFirst().title());
        assertEquals(Severity.CRITICAL, AttentionRules.evaluate(facts("PRODUCTION", true,
                List.of(new RangeFact("POS_EQUIVALENT", "CAJA", 1000, 900, TODAY.plusDays(1)))), TODAY).getFirst().severity());
        List<Attention> low = AttentionRules.evaluate(facts("PRODUCTION", true,
                List.of(new RangeFact("INVOICE", "FV", 1000, 80, TODAY.plusYears(1)))), TODAY);
        assertEquals("Quedan 80 números de facturas (FV)", low.getFirst().title());
        assertEquals(Severity.WARNING, low.getFirst().severity());
        assertEquals(Severity.CRITICAL, AttentionRules.evaluate(facts("PRODUCTION", true,
                List.of(new RangeFact("INVOICE", "FV", 1000, 20, TODAY.plusYears(1)))), TODAY).getFirst().severity());
    }

    @Test
    @DisplayName("plan y cuota: vencido, por vencer, 80 % y límite; documentos rechazados primero por gravedad")
    void planAndDocuments() {
        Facts f = new Facts("PRODUCTION", true, List.of(HEALTHY), 40, 33, false, "ACTIVE", TODAY.plusDays(3), 2, 0, 1, 4);
        List<Attention> list = AttentionRules.evaluate(f, TODAY);
        assertEquals(List.of("DOCUMENTS_REJECTED", "SUBSCRIPTION_ENDING", "QUOTA_HIGH", "DOCUMENTS_UNCERTAIN", "DRAFTS_PENDING"),
                list.stream().map(Attention::code).toList());
        assertEquals("Tu plan vence en 3 días", list.get(1).title());
        assertEquals(List.of("SUBSCRIPTION_PAST_DUE", "QUOTA_REACHED"), codes(
                new Facts("PRODUCTION", true, List.of(HEALTHY), 20, 20, true, "PAST_DUE", TODAY.minusDays(2), 0, 0, 0, 0)));
        assertEquals("QUOTA_HIGH:40", list.get(2).key(), "la clave permite no repetir el aviso");
    }
}
