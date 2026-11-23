package net.exylia.lib.guide;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Named places one plugin knows and another points at, without either depending on the other: a journey's beacon
 * over "the clicker's sell station" names {@code exylia:clicker:station:sell}, and the clicker answers with the
 * station nearest that player.
 *
 * <pre>{@code
 * // the plugin that knows the places
 * GuidePlaces.register(this, "exylia:clicker", (player, rest) -> nearestStation(player, rest));
 *
 * // any plugin that points at one
 * Location at = GuidePlaces.locate(player, "exylia:clicker:station:sell");   // null: unknown or nowhere now
 * }</pre>
 *
 * <p>A key belongs to the longest registered prefix it starts with (followed by {@code :} or nothing); the
 * resolver gets the rest of the key ({@code station:sell}, or {@code ""}). Keys ignore case. The resolver runs on
 * the caller's thread, which for a guide is the player's: read memory only. A plugin that is disabled answers
 * nothing; registering a prefix again replaces it.
 *
 * @since 1.648.0
 */
public final class GuidePlaces {

    private record Provider(Plugin plugin, BiFunction<Player, String, @Nullable Location> resolver) {
    }

    private static final Map<String, Provider> PROVIDERS = new ConcurrentHashMap<>();

    private GuidePlaces() {
    }

    /** Answers where the keys under {@code prefix} lead, for one player at a time. */
    public static void register(@NotNull Plugin plugin, @NotNull String prefix,
                                @NotNull BiFunction<Player, String, @Nullable Location> resolver) {
        PROVIDERS.put(normalise(prefix), new Provider(Objects.requireNonNull(plugin), Objects.requireNonNull(resolver)));
    }

    /** Stops answering for {@code prefix}. */
    public static void unregister(@NotNull String prefix) {
        PROVIDERS.remove(normalise(prefix));
    }

    /** Where {@code key} leads for this player now, or {@code null} when no enabled plugin knows it or it leads nowhere. */
    public static @Nullable Location locate(@NotNull Player player, @Nullable String key) {
        if (key == null || key.isBlank()) return null;
        String wanted = normalise(key);
        String best = null;
        for (String prefix : PROVIDERS.keySet()) {
            boolean under = wanted.equals(prefix) || wanted.startsWith(prefix + ":");
            if (under && (best == null || prefix.length() > best.length())) best = prefix;
        }
        if (best == null) return null;
        Provider provider = PROVIDERS.get(best);
        if (provider == null || !provider.plugin().isEnabled()) return null;
        String rest = wanted.length() == best.length() ? "" : wanted.substring(best.length() + 1);
        try {
            Location at = provider.resolver().apply(player, rest);
            return at == null ? null : at.clone();
        } catch (RuntimeException failure) {
            provider.plugin().getLogger().warning("Could not find the place " + key + ": " + failure);
            return null;
        }
    }

    private static String normalise(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
