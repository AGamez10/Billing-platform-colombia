package co.billingplatform.shared.domain;

/**
 * Dígito de verificación (DV) del NIT colombiano: suma ponderada de los dígitos
 * (de derecha a izquierda) por los factores primos 3, 7, 13, 17, 19, 23, 29, 37, 41,
 * 43, 47, 53, 59, 67, 71, módulo 11; si el residuo es 0 o 1 el DV es el residuo, en
 * otro caso es 11 − residuo.
 */
public final class NitCheckDigit {

    private static final int[] WEIGHTS = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};

    private NitCheckDigit() {}

    public static int compute(String nit) {
        if (nit == null || !nit.matches("\\d{1,15}")) {
            throw new IllegalArgumentException("NIT must contain 1 to 15 digits");
        }
        int sum = 0;
        int length = nit.length();
        for (int i = 0; i < length; i++) {
            int digit = nit.charAt(length - 1 - i) - '0';
            sum += digit * WEIGHTS[i];
        }
        int remainder = sum % 11;
        return remainder <= 1 ? remainder : 11 - remainder;
    }
}
