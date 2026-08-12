package net.exylia.lib.util;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * Potion effects applied from a compact string.
 *
 * <p>{@code |} separates effects, {@code :} separates name, amplifier and
 * duration. Amplifier defaults to {@code 0} and duration to {@code 200} ticks
 * (10 seconds).
 *
 * <pre>{@code
 * Effects.apply(player, "SPEED:1:300|JUMP_BOOST:2:120");
 * }</pre>
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

    /** Parses a compact string into effect specs — pure data, no Bukkit types. */
    public static @NotNull ParsedEffect[] parse(@NotNull String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return new ParsedEffect[0];
        return CACHE.get(trimmed, Effects::parseUncached);
    }

    /** Parses and applies in one call. */
    public static void apply(@NotNull Player player, @NotNull String raw) {
        apply(player, parse(raw));
    }

    /** Applies pre-parsed effects. */
    public static void apply(@NotNull Player player, @NotNull ParsedEffect... effects) {
        for (ParsedEffect e : effects) {
            if (e.name.isEmpty()) continue;
            Object type = resolver.resolve(e.name());
            if (type == null) continue;
            applier.apply(player, type, e.amplifier(), e.duration());
        }
    }

    /** Removes every potion effect from a player. */
    public static void clear(@NotNull Player player) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
    }

    /** An effect spec: name, amplifier and duration. No Bukkit types. */
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

    private static volatile EffectResolver resolver
            = name -> PotionEffectType.getByName(name);
    private static volatile EffectApplier applier = (player, type, amplifier, duration) ->
            player.addPotionEffect(new PotionEffect(
                    (PotionEffectType) type, duration, amplifier, false, true, true));

    static void setResolver(@NotNull EffectResolver replacement) { resolver = replacement; }
    static void setApplier(@NotNull EffectApplier replacement) { applier = replacement; }
    static @NotNull EffectResolver getResolver() { return resolver; }
    static @NotNull EffectApplier getApplier() { return applier; }
    static void resetCache() { CACHE.invalidateAll(); }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static final Cache<String, ParsedEffect[]> CACHE = Caffeine.newBuilder()
            .maximumSize(4096)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    private static ParsedEffect[] parseUncached(String raw) {
        java.util.List<ParsedEffect> effects = new java.util.ArrayList<>();
        for (String part : raw.split("\\|")) {
            ParsedEffect effect = parseOne(part.trim());
            if (effect != null) effects.add(effect);
        }
        return effects.toArray(new ParsedEffect[0]);
    }

    private static @Nullable ParsedEffect parseOne(String part) {
        if (part.isEmpty()) return null;
        String[] pieces = part.split(":", -1);
        String name = pieces[0].trim().toUpperCase();
        int amplifier = pieces.length > 1 ? parseInt(pieces[1].trim(), 0) : 0;
        int duration = pieces.length > 2 ? parseInt(pieces[2].trim(), 200) : 200;
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
