package net.exylia.lib.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpTest {

    /** RFC 6238 appendix B, SHA1 key, truncated to the six digits apps show. */
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    void matchesRfc6238Vectors() {
        assertEquals("287082", Totp.code(RFC_SECRET, Totp.step(59_000L)));
        assertEquals("081804", Totp.code(RFC_SECRET, Totp.step(1_111_111_109_000L)));
        assertEquals("050471", Totp.code(RFC_SECRET, Totp.step(1_111_111_111_000L)));
        assertEquals("005924", Totp.code(RFC_SECRET, Totp.step(1_234_567_890_000L)));
        assertEquals("279037", Totp.code(RFC_SECRET, Totp.step(2_000_000_000_000L)));
    }

    @Test
    void acceptsOneStepOfDriftAndNoMore() {
        long now = 1_234_567_890_000L;
        long step = Totp.step(now);
        assertEquals(step - 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now));
        assertEquals(step + 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now));
        assertEquals(-1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now));
        assertEquals(step, Totp.verify(RFC_SECRET, "005 924", now));
        assertEquals(-1, Totp.verify(RFC_SECRET, "00592", now));
        assertEquals(-1, Totp.verify(RFC_SECRET, "abcdef", now));
    }

    @Test
    void base32RoundTripsAndSecretsAre160Bits() {
        byte[] data = {1, 2, 3, (byte) 0xff, 0, 42, 7};
        assertArrayEquals(data, Totp.base32Decode(Totp.base32(data)));
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", RFC_SECRET);
        assertEquals(32, Totp.newSecret().length());
    }

    @Test
    void uriIsWhatAppsImport() {
        String uri = Totp.uri("My Server", "Steve", "ABC");
        assertTrue(uri.startsWith("otpauth://totp/My%20Server:Steve?secret=ABC&issuer=My%20Server"), uri);
    }
}
