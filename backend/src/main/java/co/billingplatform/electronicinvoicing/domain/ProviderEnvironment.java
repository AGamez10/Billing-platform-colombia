package co.billingplatform.electronicinvoicing.domain;

/**
 * Ambiente en el que se procesa un documento.
 *
 * <ul>
 *   <li>{@link #SIMULATION}: adaptador simulado interno. Nada llega a un proveedor ni a la DIAN.</li>
 *   <li>{@link #HABILITATION}: ambiente de pruebas/habilitación del proveedor o de la DIAN.
 *       Un documento validado aquí NO tiene validez tributaria.</li>
 *   <li>{@link #PRODUCTION}: único ambiente cuyos documentos validados tienen efectos legales.</li>
 * </ul>
 */
public enum ProviderEnvironment {
    SIMULATION,
    HABILITATION,
    PRODUCTION;

    public boolean hasLegalEffect() {
        return this == PRODUCTION;
    }
}
