package co.billingplatform.shared.domain;

/**
 * Tipos de documento de identificación de una parte (emisor o adquiriente).
 *
 * <p>Los códigos numéricos que exige el estándar DIAN se asignan en el adaptador de
 * cada proveedor/formato, no aquí. La lista cubre el MVP; se amplía según el Anexo
 * Técnico vigente cuando se implemente el primer adaptador real.
 */
public enum IdentificationType {
    NIT,
    CEDULA_CIUDADANIA,
    CEDULA_EXTRANJERIA,
    PASAPORTE,
    TARJETA_IDENTIDAD,
    DOCUMENTO_EXTRANJERO
}
