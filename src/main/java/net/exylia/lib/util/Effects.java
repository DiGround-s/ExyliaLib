package net.exylia.lib.util;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Potion effects applied from a compact string.
 *
 * <p>One effect per line, fields separated by {@code |} — the notation every
 * Exylia config already writes, taken from ExyliaCommons unchanged:
 *
 * <pre>{@code
 * Effects.apply(player, "SPEED|2|5");                 // Speed II, 5 seconds
 * Effects.apply(player, classDef.getPassiveEffects()); // a list of such lines
 * }</pre>
 *
 * <p>The notation is {@code NAME|LEVEL|SECONDS}:
 *
 * <ul>
 *   <li>{@code LEVEL} is written the way a player reads it — {@code SPEED|2}
 *       is Speed II, which Bukkit calls amplifier 1. Missing means I.
 *   <li>{@code SECONDS} is a duration in seconds; the words {@code infinite}
 *       and {@code -1} mean the effect does not end on its own. Missing means
 *       10 seconds.
 * </ul>
 *
 * <p>Anything malformed — an empty line, a name with a colon from some other
 * notation, an unparseable number — is skipped, never fatal.
 *
 * <h2>Effects that stay</h2>
 * State a plugin owns — a class passive, a kit buff — is applied with
 * {@link #applyInfinite} and taken back with {@link #remove}, which name the
 * effects rather than clearing the player:
 *
 * <pre>{@code
 * Effects.applyInfinite(player, classDef.passiveEffects());
 * Effects.remove(player, classDef.passiveEffects());
 * }</pre>
 *
 * <p>{@code applyInfinite} forces the infinite duration regardless of what the
 * line says; writing {@code |infinite} on the line achieves the same through
 * {@link #apply}. {@link #clear} exists for the case where the player really
 * should end up with nothing, such as respawning into a lobby.
 *
 * <h2>Caching</h2>
 * The same config string is parsed once and held for 30 seconds.
 *
 * @since 1.9.0
 */
public final class Effects {

    private Effects() {
        throw new AssertionError("No instances.");
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Parses one config line into an effect spec — pure data, no Bukkit types.
     *
     * @param raw the line, in {@code NAME|LEVEL|SECONDS} notation
     * @return the parsed effect, or {@code null} when the line is malformed
     */
    public static @Nullable ParsedEffect parse(@NotNull String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        return CACHE.get(trimmed, Effects::parseUncached);
    }

    /**
     * Parses a list of config lines, skipping the malformed ones.
     *
     * @param lines one effect per element
     * @return the parsed effects, in order
     */
    public static @NotNull List<ParsedEffect> parse(@NotNull List<String> lines) {
        List<ParsedEffect> effects = new ArrayList<>(lines.size());
        for (String line : lines) {
            ParsedEffect effect = parse(line);
            if (effect != null) effects.add(effect);
        }
        return effects;
    }

    /** Parses and applies one line in one call. */
    public static void apply(@NotNull Player player, @NotNull String raw) {
        ParsedEffect effect = parse(raw);
        if (effect != null) apply(player, effect);
    }

    /**
     * Parses and applies every line of a list.
     *
     * <p>This is how production configs actually hand effects over — one line
     * per effect:
     *
     * <pre>{@code
     * Effects.apply(player, classDef.getPassiveEffects());
     * }</pre>
     *
     * @param player the player
     * @param lines  one effect per element
     */
    public static void apply(@NotNull Player player, @NotNull List<String> lines) {
        for (ParsedEffect effect : parse(lines)) {
            apply(player, effect);
        }
    }

    /** Applies pre-parsed effects. */
    public static void apply(@NotNull Player player, @NotNull ParsedEffect... effects) {
        for (ParsedEffect e : effects) {
            if (e.name().isEmpty()) continue;
            Object type = resolver.resolve(e.name());
            if (type == null) continue;
            applier.apply(player, type, e.amplifier(), e.duration());
        }
    }

    /**
     * Applies effects that last until something removes them.
     *
     * <p>For state a plugin owns rather than times: a class passive, a kit
     * buff, an area effect. The duration written in the line is overridden,
     * because an effect that ends on its own is not what the caller asked for.
     *
     * <p>Pair with {@link #remove(Player, String)}, never with
     * {@link #clear(Player)}: the player may be carrying effects from a potion
     * they drank or from another plugin, and those are not this caller's to
     * take away.
     *
     * @param player the player
     * @param raw    the same notation as {@link #apply}; the duration is overridden
     */
    public static void applyInfinite(@NotNull Player player, @NotNull String raw) {
        ParsedEffect effect = parse(raw);
        if (effect != null) applyInfinite(player, effect);
    }

    /** Applies every line of a list with no end — the form configs hand over. */
    public static void applyInfinite(@NotNull Player player, @NotNull List<String> lines) {
        for (ParsedEffect effect : parse(lines)) {
            applyInfinite(player, effect);
        }
    }

    /** Applies pre-parsed effects with no end. */
    public static void applyInfinite(@NotNull Player player, @NotNull ParsedEffect... effects) {
        for (ParsedEffect e : effects) {
            if (e.name().isEmpty()) continue;
            Object type = resolver.resolve(e.name());
            if (type == null) continue;
            applier.apply(player, type, e.amplifier(), INFINITE);
        }
    }

    /**
     * Removes the named effects from a player.
     *
     * <p>Only the effects named are taken away, so a caller undoes exactly
     * what it did. Level and duration in the line are ignored: the name is
     * all that identifies a running effect.
     *
     * @param player the player
     * @param raw    the same notation as {@link #apply}; only the name is read
     */
    public static void remove(@NotNull Player player, @NotNull String raw) {
        ParsedEffect effect = parse(raw);
        if (effect != null) remove(player, effect);
    }

    /** Removes every named effect from a list — the form configs hand over. */
    public static void remove(@NotNull Player player, @NotNull List<String> lines) {
        for (ParsedEffect effect : parse(lines)) {
            remove(player, effect);
        }
    }

    /** Removes pre-parsed effects by name. */
    public static void remove(@NotNull Player player, @NotNull ParsedEffect... effects) {
        for (ParsedEffect e : effects) {
            if (e.name().isEmpty()) continue;
            Object type = resolver.resolve(e.name());
            if (type == null) continue;
            remover.remove(player, type);
        }
    }

    /** Removes every potion effect from a player. */
    public static void clear(@NotNull Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    /**
     * An effect spec: name, amplifier and duration in ticks. No Bukkit types.
     *
     * <p>The amplifier is Bukkit's — zero-based, so what a config writes as
     * {@code SPEED|2} arrives here as amplifier 1. The duration is always in
     * ticks, whatever unit the line used; {@link #INFINITE} when the line
     * asked for an effect that does not end.
     */
    public record ParsedEffect(@NotNull String name, int amplifier, int duration) {
        public ParsedEffect {
            if (name.isBlank()) throw new IllegalArgumentException("an effect needs a name");
        }
    }

    // ------------------------------------------------------------------
    // Injectables for tests
    // ------------------------------------------------------------------

    @FunctionalInterface
    interface EffectResolver {
        @Nullable Object resolve(@NotNull String name);
    }

    @FunctionalInterface
    interface EffectApplier {
        void apply(Player player, Object type, int amplifier, int duration);
    }

    @FunctionalInterface
    interface EffectRemover {
        void remove(Player player, Object type);
    }

    private static volatile EffectResolver resolver
            = name -> PotionEffectType.getByName(name);
    private static volatile EffectApplier applier = (player, type, amplifier, duration) ->
            player.addPotionEffect(new PotionEffect(
                    (PotionEffectType) type, duration, amplifier, false, true, true));
    private static volatile EffectRemover remover = (player, type) ->
            player.removePotionEffect((PotionEffectType) type);

    static void setResolver(@NotNull EffectResolver replacement) { resolver = replacement; }
    static void setApplier(@NotNull EffectApplier replacement) { applier = replacement; }
    static void setRemover(@NotNull EffectRemover replacement) { remover = replacement; }
    static @NotNull EffectResolver getResolver() { return resolver; }
    static @NotNull EffectApplier getApplier() { return applier; }
    static @NotNull EffectRemover getRemover() { return remover; }
    static void resetCache() { CACHE.invalidateAll(); }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * What the server takes to mean "until removed".
     *
     * <p>Spelled out rather than taken from {@code PotionEffect.INFINITE_DURATION}
     * so this class keeps compiling against older server API, which is the
     * same reason the rest of the module avoids Bukkit types where it can.
     */
    private static final int INFINITE = -1;

    private static final int DEFAULT_DURATION = 200; // 10 seconds

    private static final Cache<String, ParsedEffect> CACHE = Caffeine.newBuilder()
            .maximumSize(4096)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    private static @Nullable ParsedEffect parseUncached(String raw) {
        String[] pieces = raw.split("\\|", -1);
        String name = pieces[0].trim().toUpperCase();
        // A colon in the name position means some other notation — a
        // namespaced key, or a format this class does not speak. Skipping is
        // safer than guessing, because a guessed effect applies wrong silently.
        if (name.isEmpty() || name.indexOf(':') >= 0) return null;

        int level = pieces.length > 1 ? parseInt(pieces[1].trim(), 1) : 1;
        int amplifier = Math.max(0, level - 1);

        int duration = DEFAULT_DURATION;
        if (pieces.length > 2) {
            String written = pieces[2].trim().toLowerCase();
            if (written.equals("infinite") || written.equals("-1")) {
                duration = INFINITE;
            } else {
                duration = parseInt(written, 10) * 20;
            }
        }
        return new ParsedEffect(name, amplifier, duration);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
