package net.exylia.lib.packet.palette;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.packet.internal.Palettes;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Per-player block palettes: each player is shown the same world with some blocks swapped for others,
 * and nothing changes on the server. One map, built once, seen by each player in the materials of their
 * own era.
 *
 * <pre>{@code
 * BlockPalettes palettes = BlockPalettes.of(this);
 * palettes.set(player, spruceEra);   // what is sent from now on
 * palettes.refresh(player);          // and what they already hold, sent again
 * }</pre>
 *
 * <p>Every chunk, block change, multi block change, falling block and block-break effect on its way to
 * the player goes through their palette. A player has one palette at a time, whoever set it; it is
 * forgotten when they leave, and a disabled plugin's palettes come off (the world is sent again).
 *
 * <h2>Limits</h2>
 * Items are not swapped: a player holding oak planks places what they see as spruce. Light comes from
 * the server, so a swap between a see-through and a solid block of the same shape (glass and stone)
 * leaves the light as the real block casts it. A chunk with a palette viewer is decoded and encoded
 * again for them; one without costs one map lookup. Needs PacketEvents: without it {@link #set} refuses.
 *
 * @since 1.636.0
 */
public final class BlockPalettes {

    /** Palettes whose rejected swaps were already logged. */
    private static final Set<Palette> WARNED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private final Plugin plugin;

    private BlockPalettes(Plugin plugin) {
        this.plugin = plugin;
    }

    public static @NotNull BlockPalettes of(@NotNull Plugin plugin) {
        return new BlockPalettes(plugin);
    }

    /** Whether palettes work on this server: PacketEvents is installed. */
    public static boolean isSupported() {
        return Palettes.supported();
    }

    /**
     * Shows the player the world through this palette from now on, replacing any palette they had. What
     * they already hold is not sent again: {@link #refresh} it, or set the palette before their chunks
     * are sent (on join). Any thread.
     *
     * @return {@code false}, and nothing set, when palettes do not work on this server
     */
    public boolean set(@NotNull Player player, @NotNull Palette palette) {
        if (!Palettes.supported()) return false;
        if (!palette.rejected().isEmpty() && WARNED.add(palette)) {
            Debug.of(plugin).warn("Block palette swaps left out: " + String.join("; ", palette.rejected()));
        }
        Palettes.set(plugin, player, palette, palette.worlds(), palette.table());
        return true;
    }

    /** Shows the player the world as it is from now on; {@link #refresh} to repaint what they hold. */
    public void clear(@NotNull Player player) {
        Palettes.clear(player);
    }

    /** The player's palette, whoever set it, or {@code null}. */
    public @Nullable Palette get(@NotNull Player player) {
        return Palettes.paletteOf(player);
    }

    /**
     * Sends again every chunk the player holds, nearest first and spread over ticks, so what they see
     * follows their palette at once. The server resends each chunk to everyone holding it, so call it
     * when the palette changes, not every tick. Any thread.
     */
    public void refresh(@NotNull Player player) {
        Palettes.refresh(player);
    }
}
