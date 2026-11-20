package co.billingplatform.shared.application;

import java.util.List;

/**
 * Resultado de importar un archivo: en vista previa ({@code committed = false}) no se guarda
 * nada; al confirmar se guardan solo las filas válidas y se informan las demás.
 */
public record ImportResult(boolean committed, int rows, int valid, int created, List<RowError> errors) {

    /** @param line línea del archivo (1 = encabezado) */
    public record RowError(int line, String message) {}
}
