package co.billingplatform.platform.persistence;

import co.billingplatform.shared.domain.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Único punto donde se abre una transacción de negocio y se fija el tenant para
 * Row-Level Security. El valor se fija con {@code set_config(..., true)}: es local a la
 * transacción y no se filtra a la siguiente petición que reutilice la conexión del pool.
 *
 * <p>El {@link TenantId} debe provenir del principal autenticado, nunca de la petición.
 */
@Component
public class TenantTransactions {

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    public TenantTransactions(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    public <T> T inTenant(TenantId tenantId, Supplier<T> work) {
        Objects.requireNonNull(tenantId);
        return transactions.execute(status -> {
            setLocal("app.tenant_id", tenantId.value().toString());
            return work.get();
        });
    }

    /** Para flujos de identidad que solo conocen al usuario (elegir organización, renovar sesión). */
    public <T> T asUser(UUID userId, Supplier<T> work) {
        Objects.requireNonNull(userId);
        return transactions.execute(status -> {
            setLocal("app.user_id", userId.toString());
            return work.get();
        });
    }

    /** Sin contexto: solo para las funciones SECURITY DEFINER acotadas (registro, auth, cola). */
    public <T> T withoutTenant(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private void setLocal(String setting, String value) {
        jdbc.sql("SELECT set_config(?, ?, true)").param(setting).param(value).query(String.class).single();
    }
}
