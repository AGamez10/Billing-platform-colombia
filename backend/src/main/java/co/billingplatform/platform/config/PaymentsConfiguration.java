package co.billingplatform.platform.config;

import co.billingplatform.electronicinvoicing.port.ProviderAccountRepository;
import co.billingplatform.invoicing.application.FinalizeInvoiceService;
import co.billingplatform.invoicing.application.InvoiceDraftService;
import co.billingplatform.invoicing.application.InvoiceRepository;
import co.billingplatform.invoicing.application.PosSaleService;
import co.billingplatform.payments.adapter.bold.BoldGateway;
import co.billingplatform.payments.application.ChargeService;
import co.billingplatform.payments.application.PaymentAccountService;
import co.billingplatform.payments.port.MerchantPaymentGateway;
import co.billingplatform.payments.port.PaymentPorts;
import co.billingplatform.platform.crypto.AesGcmSecretVault;
import co.billingplatform.shared.application.AuditTrail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/** Cobro en tienda con la pasarela del comercio (ADR-0020) y secretos cifrados. */
@Configuration
class PaymentsConfiguration {

    /** También resuelve secretos de proveedores de facturación (reemplaza al resolvedor vacío). */
    @Bean
    AesGcmSecretVault secretVault(JdbcClient jdbc, @Value("${billing.security.secrets-key}") String masterKey) {
        return new AesGcmSecretVault(jdbc, masterKey);
    }

    @Bean
    MerchantPaymentGateway boldGateway(@Value("${billing.payments.bold.base-url:https://integrations.api.bold.co}") URI baseUrl,
                                       @Value("${billing.payments.bold.timeout:10s}") Duration timeout) {
        return new BoldGateway(baseUrl, timeout);
    }

    @Bean
    PaymentAccountService paymentAccountService(PaymentPorts.PaymentAccounts accounts, AesGcmSecretVault vault,
                                                MerchantPaymentGateway gateway, AuditTrail audit) {
        return new PaymentAccountService(accounts, vault, gateway, audit);
    }

    @Bean
    ChargeService chargeService(InvoiceDraftService drafts, FinalizeInvoiceService finalizer, InvoiceRepository invoices,
                                ProviderAccountRepository providerAccounts, PaymentPorts.PaymentAccounts paymentAccounts,
                                PaymentPorts.Charges charges, AesGcmSecretVault vault, MerchantPaymentGateway gateway,
                                PosSaleService.IdempotencyKeys keys, AuditTrail audit, Clock clock) {
        return new ChargeService(drafts, finalizer, invoices, providerAccounts, paymentAccounts, charges, vault, gateway,
                keys, audit, clock);
    }
}
