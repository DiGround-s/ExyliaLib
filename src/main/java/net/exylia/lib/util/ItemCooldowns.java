package net.exylia.lib.util;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Locale;

/**
 * Cooldowns on items, drawn by the client.
 *
 * <p>{@link Cooldowns} with two things added: the vanilla cooldown overlay
 * Minecraft draws over an item stack, and a key derived from the material so
 * two plugins that both cool down ender pearls agree about it.
 *
 * <pre>{@code
 * if (!ItemCooldowns.tryStart(player, Material.ENDER_PEARL, Duration.ofSeconds(16))) {
 *     return; // the client is already showing the sweep
 * }
 * }</pre>
 *
 * <h2>Why not just Bukkit's own</h2>
 * {@code Player#setCooldown} draws the overlay and blocks the item, which is
 * most of the job. What it will not do is survive a restart, apply to anything
 * that is not a material, or tell another plugin what is going on. This keeps
 * Bukkit's overlay — because it is the right way to show a player their
 * cooldown — and keeps the authoritative answer here.
 *
 * <h2>Custom items</h2>
 * An item that is not just its material — a named wand, a custom ability — gets
 * its own key and still shows the overlay of whatever material it happens to
 * be:
 *
 * <pre>{@code
 * ItemCooldowns.tryStart(player, "fire-wand", Material.BLAZE_ROD, Duration.ofSeconds(30));
 * }</pre>
 *
 * @since 1.11.0
 */
public final class ItemCooldowns {

    private ItemCooldowns() {
        throw new AssertionError("No instances.");
    }

    /** The prefix every item cooldown key gets. */
    private static final String NAMESPACE = "item:";

    // ------------------------------------------------------------------
    // By material
    // ------------------------------------------------------------------

    /**
     * Puts a material on cooldown and shows the client overlay.
     */
    public static void start(@NotNull Player player, @NotNull Material material,
                             @NotNull Duration duration) {
        start(player, material.name().toLowerCase(), material, duration);
    }

    /**
     * Starts the cooldown and returns whether the item was free to use.
     *
     * <p>The whole guard in one call, which is how it is almost always used.
     */
    public static boolean tryStart(@NotNull Player player, @NotNull Material material,
                                   @NotNull Duration duration) {
        return tryStart(player, material.name().toLowerCase(), material, duration);
    }

    /**
     * Returns whether the player has any item cooldown running at all.
     *
     * <p>One question instead of one per item, for the caller drawing a
     * readout every tick: an action bar that asks about sixty items finds out
     * here, in a single lookup, that there is nothing to draw.
     */
    public static boolean anyActive(@NotNull Player player) {
        return Cooldowns.hasAny(CooldownScope.player(player.getUniqueId()), NAMESPACE);
    }

    /** Returns whether a material is on cooldown for a player. */
    public static boolean isActive(@NotNull Player player, @NotNull Material material) {
        return Cooldowns.isActive(player, keyOf(material));
    }

    /** Returns what is left on a material, or zero. */
    public static @NotNull Duration remaining(@NotNull Player player,
                                              @NotNull Material material) {
        return Cooldowns.remaining(player, keyOf(material));
    }

    /** Returns the seconds left on a material, decimals included. */
    public static double remainingSeconds(@NotNull Player player, @NotNull Material material) {
        return Cooldowns.remainingSeconds(player, keyOf(material));
    }

    /** Returns what is left on a material, written for a player to read. */
    public static @NotNull String remainingFormatted(@NotNull Player player,
                                                     @NotNull Material material) {
        return Cooldowns.remainingFormatted(player, keyOf(material));
    }

    /** Ends a material's cooldown early, clearing the overlay with it. */
    public static void clear(@NotNull Player player, @NotNull Material material) {
        Cooldowns.clear(player, keyOf(material));
        overlay.show(player, material, 0);
    }

    // ------------------------------------------------------------------
    // By name, for items that are more than their material
    // ------------------------------------------------------------------

    /**
     * Puts a named item on cooldown and shows the overlay on a material.
     *
     * @param key      what this item is, independent of what it is made of
     * @param material the material whose overlay the player sees
     */
    public static void start(@NotNull Player player, @NotNull String key,
                             @NotNull Material material, @NotNull Duration duration) {
        Cooldowns.start(player, NAMESPACE + key, duration);
        overlay.show(player, material, ticksOf(duration));
    }

    /** Starts a named item's cooldown and returns whether it was free. */
    public static boolean tryStart(@NotNull Player player, @NotNull String key,
                                   @NotNull Material material, @NotNull Duration duration) {
        if (Cooldowns.isActive(player, NAMESPACE + key)) {
            return false;
        }
        start(player, key, material, duration);
        return true;
    }

    /** Returns whether a named item is on cooldown. */
    public static boolean isActive(@NotNull Player player, @NotNull String key) {
        return Cooldowns.isActive(player, NAMESPACE + key);
    }

    /** Returns what is left on a named item, or zero. */
    public static @NotNull Duration remaining(@NotNull Player player, @NotNull String key) {
        return Cooldowns.remaining(player, NAMESPACE + key);
    }

    /** Returns the seconds left on a named item, decimals included. */
    public static double remainingSeconds(@NotNull Player player, @NotNull String key) {
        return Cooldowns.remainingSeconds(player, NAMESPACE + key);
    }

    /** Returns what is left on a named item, written for a player to read. */
    public static @NotNull String remainingFormatted(@NotNull Player player,
                                                     @NotNull String key) {
        return Cooldowns.remainingFormatted(player, NAMESPACE + key);
    }

    /** Ends a named item's cooldown early, clearing an overlay with it. */
    public static void clear(@NotNull Player player, @NotNull String key,
                             @NotNull Material material) {
        Cooldowns.clear(player, NAMESPACE + key);
        overlay.show(player, material, 0);
    }

    /**
     * Redraws the overlay for what a player still has running.
     *
     * <p>The client forgets the sweep when the player reconnects, so a
     * cooldown that survived on the server would look free until it was used.
     * ExyliaLib calls this on join for the materials a caller registered.
     */
    public static void restore(@NotNull Player player, @NotNull Material material) {
        long left = Cooldowns.remaining(player, keyOf(material))
                .toMillis();
        if (left > 0) {
            overlay.show(player, material, (int) (left / 50L));
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * The cooldown key a material answers to, built once per material.
     *
     * <p>The key never changes, and a readout asks for it several times a
     * second for every item it draws; lowercasing the enum name each time is
     * two allocations per question for a string that is always the same.
     */
    private static String keyOf(Material material) {
        int ordinal = material.ordinal();
        String[] keys = MATERIAL_KEYS;
        if (ordinal >= keys.length) {
            // A material this build has never seen: the server is newer than
            // the API we compiled against.
            return NAMESPACE + material.name().toLowerCase(Locale.ROOT);
        }
        String key = keys[ordinal];
        if (key == null) {
            key = NAMESPACE + material.name().toLowerCase(Locale.ROOT);
            keys[ordinal] = key;
        }
        return key;
    }

    /** Filled on first use, one slot per material. */
    private static final String[] MATERIAL_KEYS = new String[Material.values().length];

    private static int ticksOf(Duration duration) {
        long ticks = duration.toMillis() / 50L;
        // Bukkit takes an int, and anything past a few days of cooldown is
        // better shown as "not now" than as an overflowed sweep.
        return ticks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }

    /** How the overlay gets drawn. Injectable so tests need no client. */
    @FunctionalInterface
    interface Overlay {
        void show(Player player, Material material, int ticks);
    }

    private static volatile Overlay overlay = Player::setCooldown;

    /** For tests: replaces the overlay with one that records. */
    static void setOverlay(@NotNull Overlay replacement) {
        overlay = replacement;
    }

    /** For tests: restores the real overlay. */
    static void resetOverlay() {
        overlay = Player::setCooldown;
    }
}
