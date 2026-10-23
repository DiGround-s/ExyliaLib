package net.exylia.lib.util;

import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Time-based one-time passwords (RFC 6238), the six digits an authenticator
 * app shows: Google Authenticator, Authy, 1Password, Aegis and the rest.
 *
 * <pre>{@code
 * String secret = Totp.newSecret();                       // store it, sealed
 * String uri = Totp.uri("Exylia", player.getName(), secret);  // QrCodes.mapPixels(uri)
 *
 * long step = Totp.verify(secret, typed, System.currentTimeMillis());
 * if (step < 0 || step <= lastUsedStep) refuse();          // wrong, or replayed
 * else { lastUsedStep = step; accept(); }
 * }</pre>
 *
 * <p>The parameters every app agrees on and none lets you change reliably:
 * HMAC-SHA1, 30-second steps, six digits. {@link #verify} accepts the step
 * before and after the current one, so a phone clock a few seconds off still
 * works; it answers the step that matched so a caller can refuse a code that
 * was already used.
 *
 * <p>Pure Java with no server types: safe from any thread, and the time is a
 * parameter so tests never wait.
 */
public final class Totp {

    /** Seconds one code lives. */
    public static final int STEP_SECONDS = 30;
    /** Digits in a code. */
    public static final int DIGITS = 6;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A new random secret: 160 bits, base32 without padding, as apps expect it typed. */
    public static @NotNull String newSecret() {
        byte[] key = new byte[20];
        RANDOM.nextBytes(key);
        return base32(key);
    }

    /**
     * The {@code otpauth://} link an app imports, usually as a QR code.
     *
     * @param issuer  what the app files the entry under (the server's name)
     * @param account who it is for, shown under the issuer
     */
    public static @NotNull String uri(@NotNull String issuer, @NotNull String account, @NotNull String secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    /** The step a moment falls in. */
    public static long step(long epochMillis) {
        return Math.floorDiv(epochMillis, STEP_SECONDS * 1000L);
    }

    /** The code for one step, zero-padded to {@link #DIGITS}. */
    public static @NotNull String code(@NotNull String secret, long step) {
        byte[] key = base32Decode(secret);
        byte[] counter = new byte[8];
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) step;
            step >>>= 8;
        }
        byte[] hash;
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            hash = mac.doFinal(counter);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
        return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % 1_000_000);
    }

    /**
     * Checks a typed code against the current step and the one on either side.
     *
     * <p>Spaces are ignored ({@code 123 456} is how apps display it); anything
     * else that is not six digits is simply wrong. The comparison takes the
     * same time whichever digit differs.
     *
     * @return the step the code belongs to, or {@code -1} when it matches none
     */
    public static long verify(@NotNull String secret, @NotNull String typed, long epochMillis) {
        String code = typed.replace(" ", "");
        if (code.length() != DIGITS || !code.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        long now = step(epochMillis);
        for (long step = now - 1; step <= now + 1; step++) {
            if (java.security.MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                return step;
            }
        }
        return -1;
    }

    /** Base32 (RFC 4648) without padding. */
    public static @NotNull String base32(byte @NotNull [] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >>> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    /** Reads base32 the way people type it: any case, spaces and padding ignored. */
    public static byte @NotNull [] base32Decode(@NotNull String text) {
        String clean = text.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = BASE32.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Not base32: " + clean.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >>> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
