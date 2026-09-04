package net.exylia.lib.block;

import net.exylia.lib.block.internal.BlockRuntime;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One plugin's view of the clickable block module.
 *
 * <pre>{@code
 * private PluginBlocks blocks;
 *
 * public void onEnable() {
 *     blocks = Blocks.of(this);
 * }
 *
 * blocks.at(crate.location())
 *         .onRight(click -> open(click.player(), crate))
 *         .onLeft(click -> preview(click.player(), crate))
 *         .register();
 * }</pre>
 *
 * @since 1.110.0
 */
public final class PluginBlocks {

    private final Plugin plugin;

    PluginBlocks(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /** The plugin these belong to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /**
     * Starts a registration at a location.
     *
     * <p>The location is taken to the block that contains it, so a player's
     * feet and the centre of a block name the same one.
     *
     * @param location where the block is
     * @return the builder; nothing is registered until {@code register()}
     */
    public @NotNull ClickableBlock.Builder at(@NotNull Location location) {
        return new ClickableBlock.Builder(plugin, location);
    }

    /** The same, from a block. */
    public @NotNull ClickableBlock.Builder at(@NotNull Block block) {
        return new ClickableBlock.Builder(plugin, block.getLocation());
    }

    /**
     * What this plugin registered at a location, or {@code null} — including
     * {@code null} when another plugin owns it.
     *
     * @param location where to look
     * @return the registration, or {@code null}
     */
    public @Nullable ClickableBlock registered(@NotNull Location location) {
        ClickableBlock block = BlockRuntime.at(location);
        return block != null && block.owner().equals(plugin.getName()) ? block : null;
    }

    /**
     * Takes down what this plugin registered at a location.
     *
     * @param location where to look
     * @return whether something was taken down
     */
    public boolean unregister(@NotNull Location location) {
        ClickableBlock block = registered(location);
        if (block == null) return false;
        block.unregister();
        return true;
    }

    /** How many this plugin has registered. */
    public int count() {
        return BlockRuntime.countOf(plugin.getName());
    }

    /**
     * Takes down everything this plugin registered.
     *
     * <p>Not needed on disable — the library does it — but it is what a reload
     * wants before registering what the config now says.
     */
    public void unregisterAll() {
        BlockRuntime.releaseOwner(plugin.getName());
    }
}
