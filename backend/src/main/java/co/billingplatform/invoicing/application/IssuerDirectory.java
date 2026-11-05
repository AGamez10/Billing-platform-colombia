package co.billingplatform.invoicing.application;

import co.billingplatform.shared.domain.Party;
import co.billingplatform.shared.domain.TenantId;

/** Datos tributarios del emisor (el tenant). Lo implementa el módulo de organizaciones. */
public interface IssuerDirectory {

    Party issuerOf(TenantId tenantId);
}
