package co.billingplatform.identity.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Códigos de recuperación para entrar si se pierde el teléfono: 10 códigos de un solo uso con
 * 50 bits aleatorios cada uno ({@code XXXXX-XXXXX}), sin caracteres que se confundan al
 * copiarlos a mano (sin I, O, 0 ni 1). Se guardan solo como hash.
 */
public final class RecoveryCodes {

    public static final int COUNT = 10;
    static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {}

    public static List<String> generate() {
        List<String> codes = new ArrayList<>(COUNT);
        for (int n = 0; n < COUNT; n++) {
            StringBuilder code = new StringBuilder(LENGTH + 1);
            for (int i = 0; i < LENGTH; i++) {
                if (i == LENGTH / 2) {
                    code.append('-');
                }
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            codes.add(code.toString());
        }
        return codes;
    }

    /** Sin guiones ni espacios y en mayúsculas; null si no tiene la forma de un código. */
    public static String normalize(String presented) {
        if (presented == null) {
            return null;
        }
        String clean = presented.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (clean.length() != LENGTH) {
            return null;
        }
        for (char c : clean.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) {
                return null;
            }
        }
        return clean;
    }
}
