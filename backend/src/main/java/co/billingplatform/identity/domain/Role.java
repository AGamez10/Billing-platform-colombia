package co.billingplatform.identity.domain;

import java.util.EnumSet;
import java.util.Set;

import static co.billingplatform.identity.domain.Permission.*;

/** Rol de un usuario dentro de una organización (tabla {@code memberships}). */
public enum Role {
    /** Dueño: todo, incluido configurar el proveedor tecnológico. */
    OWNER(EnumSet.allOf(Permission.class)),
    /** Administrador: todo menos el proveedor tecnológico. */
    ADMIN(EnumSet.complementOf(EnumSet.of(PROVIDER_MANAGE))),
    /** Facturador: opera clientes, productos y facturas. */
    BILLER(EnumSet.of(INVOICE_READ, INVOICE_WRITE, INVOICE_FINALIZE, CUSTOMER_READ, CUSTOMER_WRITE,
            PRODUCT_READ, PRODUCT_WRITE)),
    /** Consulta: solo lectura. */
    VIEWER(EnumSet.of(INVOICE_READ, CUSTOMER_READ, PRODUCT_READ));

    private final Set<Permission> permissions;

    Role(EnumSet<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    /** Un rol solo puede asignar roles que no superen los suyos (evita escalada). */
    public boolean canGrant(Role other) {
        return can(USERS_MANAGE) && permissions.containsAll(other.permissions);
    }
}
