package co.billingplatform.subscriptions.application;

import java.util.List;

/** Planes públicos y activos, para la página de precios y el registro. */
public interface PlanCatalog {

    /**
     * Montos como texto decimal (nunca punto flotante en la API).
     *
     * @param quotaDocuments  documentos por ciclo (MONTHLY) o en total (TOTAL); {@code null} = sin límite
     * @param annualPrice     {@code null} si el plan no se vende anual
     * @param overageUnitPrice {@code null} si el plan no admite excedentes
     * @param family          TRIAL, GENERAL (pocos documentos) o COMMERCE (un documento por venta)
     */
    record PublicPlan(String code, String name, String family, Integer quotaDocuments, String quotaScope, Integer maxUsers,
                      String monthlyPrice, boolean monthlyBillingAllowed, String annualPrice, String overageUnitPrice) {}

    List<PublicPlan> publicPlans();
}
