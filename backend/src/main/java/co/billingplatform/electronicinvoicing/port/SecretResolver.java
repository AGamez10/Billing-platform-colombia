package co.billingplatform.electronicinvoicing.port;

/**
 * Resuelve una referencia de secreto a su valor. La implementación de MVP lee secretos
 * cifrados en base de datos (AES-GCM con llave maestra en variable de entorno, ver
 * docs/SECURITY.md); puede sustituirse por un gestor de secretos sin tocar adaptadores.
 *
 * <p>El valor devuelto nunca debe registrarse en logs.
 */
public interface SecretResolver {

    byte[] resolve(String secretRef);
}
