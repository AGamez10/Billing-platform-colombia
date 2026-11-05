package co.billingplatform.platform;

import co.billingplatform.platform.persistence.TenantTransactions;
import co.billingplatform.shared.domain.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationStartupTest extends PostgresIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantTransactions transactions;

    @Test
    @DisplayName("la aplicación arranca, Flyway migra como dueño y la app opera como billing_app")
    void startsWithLeastPrivilegeConnection() {
        assertEquals("billing_app", jdbc.sql("SELECT current_user").query(String.class).single());
        assertEquals(5, jdbc.sql("SELECT count(*) FROM tax_rates").query(Long.class).single());
    }

    @Test
    @DisplayName("el tenant se fija por transacción y no se filtra a la siguiente")
    void tenantIsTransactionLocal() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        String inside = transactions.inTenant(tenant,
                () -> jdbc.sql("SELECT current_setting('app.tenant_id', true)").query(String.class).single());
        assertEquals(tenant.toString(), inside);

        String after = transactions.withoutTenant(
                () -> jdbc.sql("SELECT coalesce(current_setting('app.tenant_id', true), '')").query(String.class).single());
        assertEquals("", after);
    }
}
