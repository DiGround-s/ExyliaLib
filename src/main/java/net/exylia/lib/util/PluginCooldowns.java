package net.exylia.lib.util;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.UUID;

/**
 * {@link Cooldowns} with every key prefixed, so one plugin's keys cannot
 * collide with another's.
 *
 * <pre>{@code
 * private final PluginCooldowns cooldowns = Cooldowns.forPlugin(this);
 *
 * if (!cooldowns.tryStart(player, "pearl", Duration.ofSeconds(16))) {
 *     return;
 * }
 * }</pre>
 *
 * <p>The key written is {@code "myplugin:pearl"}, which is what the underlying
 * store sees. Two plugins can both call their cooldown {@code "pearl"} without
 * ever meeting.
 *
 * <p>Holding one of these costs a string concatenation per call, which is why
 * the un-prefixed API is still there: a plugin that already namespaces its own
 * keys should keep using it.
 *
 * @since 1.11.0
 */
public final class PluginCooldowns {

    private final String prefix;

    PluginCooldowns(String namespace) {
        this.prefix = namespace + ":";
    }

    // ------------------------------------------------------------------
    // Starting
    // ------------------------------------------------------------------

    /** Puts a key on cooldown for a player. */
    public void start(@NotNull Player player, @NotNull String key, @NotNull Duration duration) {
        Cooldowns.start(player, prefix + key, duration);
    }

    /** Puts a key on cooldown for a player. */
    public void start(@NotNull UUID player, @NotNull String key, @NotNull Duration duration) {
        Cooldowns.start(player, prefix + key, duration);
    }

    /** Puts a key on cooldown for any owner. */
    public void start(@NotNull CooldownScope scope, @NotNull String key,
                      @NotNull Duration duration) {
        Cooldowns.start(scope, prefix + key, duration);
    }

    /** Puts a key on cooldown for a number of seconds. */
    public void startSeconds(@NotNull Player player, @NotNull String key, long seconds) {
        Cooldowns.startSeconds(player, prefix + key, seconds);
    }

    /** Puts a key on cooldown for a number of ticks. */
    public void startTicks(@NotNull Player player, @NotNull String key, long ticks) {
        Cooldowns.startTicks(player, prefix + key, ticks);
    }

    // ------------------------------------------------------------------
    // Asking
    // ------------------------------------------------------------------

    /** Returns whether a key is still on cooldown. */
    public boolean isActive(@NotNull Player player, @NotNull String key) {
        return Cooldowns.isActive(player, prefix + key);
    }

    /** Returns whether a key is still on cooldown. */
    public boolean isActive(@NotNull UUID player, @NotNull String key) {
        return Cooldowns.isActive(player, prefix + key);
    }

    /** Returns whether a key is still on cooldown. */
    public boolean isActive(@NotNull CooldownScope scope, @NotNull String key) {
        return Cooldowns.isActive(scope, prefix + key);
    }

    /** Returns what is left, or {@link Duration#ZERO} when nothing is. */
    public @NotNull Duration remaining(@NotNull Player player, @NotNull String key) {
        return Cooldowns.remaining(player, prefix + key);
    }

    /** Returns the milliseconds left, or {@code 0} when nothing is. */
    public long remaining(@NotNull CooldownScope scope, @NotNull String key) {
        return Cooldowns.remaining(scope, prefix + key);
    }

    /** Returns the seconds left, decimals included, or {@code 0}. */
    public double remainingSeconds(@NotNull Player player, @NotNull String key) {
        return Cooldowns.remainingSeconds(player, prefix + key);
    }

    /** Returns the seconds left rounded up, for a "wait N seconds" message. */
    public long remainingWholeSeconds(@NotNull Player player, @NotNull String key) {
        return Cooldowns.remainingWholeSeconds(player, prefix + key);
    }

    /** Returns what is left, written the way a player should read it. */
    public @NotNull String remainingFormatted(@NotNull Player player, @NotNull String key) {
        return Cooldowns.remainingFormatted(player, prefix + key);
    }

    /** Returns what is left, written in the given style. */
    public @NotNull String remainingFormatted(@NotNull Player player, @NotNull String key,
                                              @NotNull TimeFormats.Style style) {
        return Cooldowns.remainingFormatted(player, prefix + key, style);
    }

    /** Starts the cooldown and returns whether it was free to begin with. */
    public boolean tryStart(@NotNull Player player, @NotNull String key,
                            @NotNull Duration duration) {
        return Cooldowns.tryStart(player, prefix + key, duration);
    }

    /** Starts the cooldown and returns whether it was free to begin with. */
    public boolean tryStart(@NotNull CooldownScope scope, @NotNull String key,
                            @NotNull Duration duration) {
        return Cooldowns.tryStart(scope, prefix + key, duration);
    }

    // ------------------------------------------------------------------
    // Clearing
    // ------------------------------------------------------------------

    /** Ends one cooldown early. */
    public void clear(@NotNull Player player, @NotNull String key) {
        Cooldowns.clear(player, prefix + key);
    }

    /** Ends one cooldown early. */
    public void clear(@NotNull CooldownScope scope, @NotNull String key) {
        Cooldowns.clear(scope, prefix + key);
    }

    /** The prefix every key from this view gets. */
    public @NotNull String namespace() {
        return prefix.substring(0, prefix.length() - 1);
    }
}
