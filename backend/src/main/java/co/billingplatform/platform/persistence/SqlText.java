package co.billingplatform.platform.persistence;

/** Utilidades para texto que entra a SQL como parámetro (nunca concatenado en la consulta). */
public final class SqlText {

    private SqlText() {}

    /** Escapa los comodines de LIKE para que un texto de búsqueda se trate literalmente (con {@code ESCAPE '\'}). */
    public static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
