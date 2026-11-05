package co.billingplatform.platform;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de pruebas de integración: PostgreSQL 16 real en un contenedor, migrado por Flyway
 * como billing_owner, con la aplicación conectada como billing_app (RLS activa), igual
 * que en producción. Un solo contenedor por ejecución.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16")
            .withInitScript("db/test-roles.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("BILLING_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("BILLING_DB_APP_PASSWORD", () -> "app-test");
        registry.add("BILLING_DB_OWNER_PASSWORD", () -> "owner-test");
    }
}
