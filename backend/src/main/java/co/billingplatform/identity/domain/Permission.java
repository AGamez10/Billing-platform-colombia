package co.billingplatform.identity.domain;

/** Permisos de la aplicación. La autorización se verifica en el backend, nunca solo en el frontend. */
public enum Permission {
    INVOICE_READ,
    INVOICE_WRITE,
    INVOICE_FINALIZE,
    CUSTOMER_READ,
    CUSTOMER_WRITE,
    PRODUCT_READ,
    PRODUCT_WRITE,
    NUMBERING_MANAGE,
    USAGE_READ,
    USERS_MANAGE,
    API_KEYS_MANAGE,
    PROVIDER_MANAGE;

    /** Permisos administrativos que nunca se delegan a una clave de API. */
    public boolean isAdministrative() {
        return this == USERS_MANAGE || this == API_KEYS_MANAGE || this == PROVIDER_MANAGE;
    }
}
