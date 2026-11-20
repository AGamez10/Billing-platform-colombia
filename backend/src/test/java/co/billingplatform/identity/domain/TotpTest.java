package co.billingplatform.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TotpTest {

    /** Secreto de los vectores de prueba del anexo B de RFC 6238 (SHA-1). */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("coincide con los vectores de RFC 6238 (últimos 6 dígitos)")
    void rfc6238Vectors() {
        assertEquals("287082", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(59))));
        assertEquals("081804", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111109))));
        assertEquals("050471", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1111111111))));
        assertEquals("005924", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(1234567890))));
        assertEquals("279037", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(2000000000))));
        assertEquals("353130", Totp.code(RFC_SECRET, Totp.stepAt(Instant.ofEpochSecond(20000000000L))));
    }

    @Test
    @DisplayName("acepta ±30 s de desfase, rechaza más y no deja reutilizar un código")
    void windowAndReplay() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = Totp.stepAt(now);
        String current = Totp.code(RFC_SECRET, step);
        assertEquals(step, Totp.verify(RFC_SECRET, current, now, 0).getAsLong());
        assertEquals(step - 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, 0).getAsLong());
        assertEquals(step + 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, 0).getAsLong());
        assertTrue(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, 0).isEmpty(), "fuera de la ventana");
        assertTrue(Totp.verify(RFC_SECRET, current, now, step).isEmpty(), "el mismo paso ya se usó");
        assertTrue(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, step).isEmpty(), "un paso anterior tampoco");
        assertTrue(Totp.verify(RFC_SECRET, "12345", now, 0).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, "abcdef", now, 0).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, null, now, 0).isEmpty());
        String spaced = current.substring(0, 3) + " " + current.substring(3);
        assertTrue(Totp.verify(RFC_SECRET, spaced, now, 0).isPresent(), "se aceptan espacios como los muestra la app");
    }

    @Test
    @DisplayName("URI otpauth con emisor y cuenta codificados")
    void provisioningUri() {
        String uri = Totp.provisioningUri("Billing Platform", "ana+caja@example.com", "JBSWY3DPEHPK3PXP");
        assertEquals("otpauth://totp/Billing%20Platform:ana%2Bcaja%40example.com?secret=JBSWY3DPEHPK3PXP"
                + "&issuer=Billing%20Platform&algorithm=SHA1&digits=6&period=30", uri);
    }

    @Test
    @DisplayName("Base32 según los vectores de RFC 4648")
    void base32() {
        assertEquals("", Base32.encode(new byte[0]));
        assertEquals("MY", Base32.encode("f".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("foobar", new String(Base32.decode("mzxw6ytboi======"), StandardCharsets.US_ASCII));
        byte[] random = new byte[20];
        new java.util.Random(7).nextBytes(random);
        assertTrue(java.util.Arrays.equals(random, Base32.decode(Base32.encode(random))));
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("01"));
    }

    @Test
    @DisplayName("códigos de recuperación: 10 distintos, legibles y normalizables")
    void recoveryCodes() {
        List<String> codes = RecoveryCodes.generate();
        assertEquals(RecoveryCodes.COUNT, codes.size());
        assertEquals(RecoveryCodes.COUNT, new HashSet<>(codes).size());
        for (String c : codes) {
            assertTrue(c.matches("[2-9A-HJ-NP-Z]{5}-[2-9A-HJ-NP-Z]{5}"), c);
            assertEquals(c.replace("-", ""), RecoveryCodes.normalize(" " + c.toLowerCase() + " "));
        }
        assertNull(RecoveryCodes.normalize("ABCDE-FGHI0")); // 0 no está en el alfabeto
        assertNull(RecoveryCodes.normalize("123456"));
        assertNull(RecoveryCodes.normalize(null));
    }
}
