package co.billingplatform.shared.domain;

/**
 * Dirección fiscal en Colombia: línea de dirección y municipio con su código DANE (DIVIPOLA) de
 * cinco dígitos, cuyos dos primeros son el departamento. Que el código exista en la DIVIPOLA lo
 * comprueba la aplicación contra el catálogo oficial; aquí solo la forma.
 */
public record FiscalAddress(String line, String cityCode) {

    public FiscalAddress {
        line = line == null ? "" : line.strip().replaceAll("\\s+", " ");
        if (line.length() < 3 || line.length() > 300) {
            throw new DomainException("INVALID_ADDRESS", "Address must have between 3 and 300 characters");
        }
        cityCode = cityCode == null ? "" : cityCode.strip();
        if (!cityCode.matches("^[0-9]{5}$")) {
            throw new DomainException("INVALID_ADDRESS", "City code must be the 5-digit DANE code");
        }
    }

    public String departmentCode() {
        return cityCode.substring(0, 2);
    }

    /** Ambas partes o ninguna: una dirección sin municipio (o al revés) no sirve para facturar. */
    public static FiscalAddress ofNullable(String line, String cityCode) {
        boolean noLine = line == null || line.isBlank();
        boolean noCity = cityCode == null || cityCode.isBlank();
        if (noLine && noCity) {
            return null;
        }
        return new FiscalAddress(line, cityCode);
    }
}
