package net.exylia.lib.client;

import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * A cooldown drawn by a modified client, next to the hotbar.
 *
 * <p>The client draws the icon and counts down on its own, so the server sends
 * one message and then nothing:
 *
 * <pre>{@code
 * Clients.cooldowns().show(player, Cooldown.of("enderpearl", Duration.ofSeconds(16))
 *         .icon(Icon.item("ENDER_PEARL")));
 * }</pre>
 *
 * <p>This is the client's own cooldown display, which is not the same thing as
 * the server refusing to let the player use the item. Whether the ability is
 * actually on cooldown is the plugin's business; this only draws it.
 *
 * @param name     the handle it is removed by, and what the client keys it on
 * @param duration how long it counts down for
 * @param icon     what the client draws
 * @since 1.7.0
 */
public record Cooldown(@NotNull String name, @NotNull Duration duration, @NotNull Icon icon) {

    public Cooldown {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a cooldown needs a name");
        }
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("a cooldown needs a duration");
        }
        if (icon == null) {
            icon = Icon.item("COMPASS");
        }
    }

    /**
     * A cooldown with the default compass icon.
     *
     * @param name     the handle it is removed by
     * @param duration how long it counts down for
     * @return the cooldown
     */
    public static @NotNull Cooldown of(@NotNull String name, @NotNull Duration duration) {
        return new Cooldown(name, duration, Icon.item("COMPASS"));
    }

    /**
     * A cooldown measured in seconds, which is how configs write it.
     *
     * @param name    the handle it is removed by
     * @param seconds how long it counts down for, decimals included
     * @return the cooldown
     */
    public static @NotNull Cooldown seconds(@NotNull String name, double seconds) {
        return of(name, Duration.ofMillis(Math.round(seconds * 1000)));
    }

    /**
     * The same cooldown with another icon.
     *
     * @param icon what the client draws
     * @return a new cooldown
     */
    public @NotNull Cooldown icon(@NotNull Icon icon) {
        return new Cooldown(name, duration, icon);
    }

    /**
     * What a client draws next to the countdown.
     *
     * <p>Either an item, which every client can render from its own textures,
     * or a texture the client fetches by resource location.
     *
     * @param item     a Bukkit material name, or {@code null} for a resource icon
     * @param resource a resource location, or {@code null} for an item icon
     * @param size     the size a resource icon is drawn at
     */
    public record Icon(String item, String resource, int size) {

        /**
         * An icon drawn from an item.
         *
         * @param material a Bukkit material name such as {@code ENDER_PEARL}
         * @return the icon
         */
        public static @NotNull Icon item(@NotNull String material) {
            return new Icon(material, null, 0);
        }

        /**
         * An icon drawn from a texture the client already has.
         *
         * @param resource a resource location
         * @param size     how big to draw it
         * @return the icon
         */
        public static @NotNull Icon resource(@NotNull String resource, int size) {
            return new Icon(null, resource, size);
        }

        /** Returns whether this icon is an item rather than a texture. */
        public boolean isItem() {
            return item != null;
        }
    }
}
