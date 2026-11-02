package net.exylia.lib.block;

import net.exylia.lib.block.internal.BlockRuntime;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A block in the world that answers clicks instead of behaving like its
 * material.
 *
 * <p>Built through {@link PluginBlocks#at(Location)}. Held only by code that
 * needs to take it back down; a plugin that registers a block for as long as it
 * is enabled can throw the handle away, because disabling it unregisters
 * everything it owns.
 *
 * @since 1.110.0
 */
public final class ClickableBlock {

    private final Plugin plugin;
    private final Location location;
    private final Consumer<BlockClick> onLeft;
    private final Consumer<BlockClick> onRight;
    private final boolean protectBlock;
    private final boolean cancelVanilla;
    private final boolean ownExplosions;
    private final boolean protectInventory;
    private final boolean ignoreCancelled;

    ClickableBlock(Plugin plugin, Location location, Consumer<BlockClick> onLeft,
                   Consumer<BlockClick> onRight, boolean protectBlock, boolean cancelVanilla,
                   boolean ownExplosions, boolean protectInventory, boolean ignoreCancelled) {
        this.ownExplosions = ownExplosions;
        this.protectInventory = protectInventory;
        this.ignoreCancelled = ignoreCancelled;
        this.plugin = plugin;
        this.location = location;
        this.onLeft = onLeft;
        this.onRight = onRight;
        this.protectBlock = protectBlock;
        this.cancelVanilla = cancelVanilla;
    }

    /** The plugin that registered it. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /** The name of the plugin that registered it. */
    public @NotNull String owner() {
        return plugin.getName();
    }

    /** Where it is. A copy, so moving it moves nothing. */
    public @NotNull Location location() {
        return location.clone();
    }

    /** Whether the world may not take it away. */
    public boolean protectedBlock() {
        return protectBlock;
    }

    /**
     * Whether the plugin that registered it decides what an explosion does to it,
     * so the library leaves it in the blast's block list.
     *
     * @since 1.438.0
     */
    public boolean ownsExplosions() {
        return ownExplosions;
    }

    /**
     * Whether hoppers, droppers and hopper minecarts may not move items into or
     * out of it.
     *
     * @since 1.458.0
     */
    public boolean protectsInventory() {
        return protectInventory;
    }

    /**
     * Whether a click another plugin already cancelled leaves it alone: no
     * handler runs.
     *
     * @since 1.458.0
     */
    public boolean ignoresCancelled() {
        return ignoreCancelled;
    }

    /** Whether a click on it is kept from doing what its material does. */
    public boolean cancelsVanilla() {
        return cancelVanilla;
    }

    /** Whether it is still the registration standing at its location. */
    public boolean isRegistered() {
        return BlockRuntime.at(location) == this;
    }

    /**
     * Takes it back down.
     *
     * <p>The block itself is left exactly as it is: what stops being true is
     * that clicking it does anything. Safe to call twice, and safe from any
     * thread.
     */
    public void unregister() {
        BlockRuntime.unregister(location, this);
    }

    /**
     * Runs the handler for a button, if there is one.
     *
     * <p>Called by the library on the thread that owns the block.
     *
     * @param click what happened
     * @return whether a handler ran
     */
    public boolean fire(@NotNull BlockClick click) {
        Consumer<BlockClick> handler = click.button() == BlockButton.LEFT ? onLeft : onRight;
        if (handler == null) return false;
        handler.accept(click);
        return true;
    }

    /**
     * Collects what a block does, before it is registered.
     *
     * <pre>{@code
     * blocks.at(crate.location())
     *         .onRight(click -> crates.open(click.player(), crate))
     *         .onLeft(click -> crates.preview(click.player(), crate))
     *         .register();
     * }</pre>
     */
    public static final class Builder {

        private final Plugin plugin;
        private final Location location;
        private Consumer<BlockClick> onLeft;
        private Consumer<BlockClick> onRight;
        private boolean protectBlock = true;
        private boolean cancelVanilla = true;
        private boolean ownExplosions;
        private boolean protectInventory;
        private boolean ignoreCancelled;

        Builder(@NotNull Plugin plugin, @NotNull Location location) {
            this.plugin = plugin;
            // The block's own corner, built by hand rather than through
            // getBlock(): asking the world for the block loads its chunk, and
            // registering is something a plugin does for every block it owns at
            // startup, long before anybody is near them.
            this.location = new Location(location.getWorld(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        /** What a left click does. Without one, a left click is only refused. */
        public @NotNull Builder onLeft(@Nullable Consumer<BlockClick> handler) {
            this.onLeft = handler;
            return this;
        }

        /** What a right click does. Without one, a right click is only refused. */
        public @NotNull Builder onRight(@Nullable Consumer<BlockClick> handler) {
            this.onRight = handler;
            return this;
        }

        /** Both buttons, for a block that does not care which was used. */
        public @NotNull Builder onClick(@Nullable Consumer<BlockClick> handler) {
            return onLeft(handler).onRight(handler);
        }

        /**
         * Whether the world may not take the block away — broken by hand,
         * blown up, pushed by a piston, burnt. On by default, because a
         * registration whose block is gone points at air.
         *
         * @param value whether to protect it
         * @return this
         */
        public @NotNull Builder protect(boolean value) {
            this.protectBlock = value;
            return this;
        }

        /**
         * Whether a click still does what the material does. Off by default: a
         * crate drawn on a barrel must not open a barrel, and a click holding a
         * block must not build.
         *
         * @param value whether to let the material act
         * @return this
         */
        public @NotNull Builder vanilla(boolean value) {
            this.cancelVanilla = !value;
            return this;
        }

        /**
         * Whether the registering plugin decides what an explosion does to the
         * block. Off by default: a protected block is taken out of every
         * explosion's block list at {@code HIGH}. On, the library leaves it in
         * the list, so the owner, listening at {@code HIGHEST}, sees whether a
         * claim plugin already took it out and handles the rest itself. Breaking
         * by hand, pistons and fire stay covered by {@link #protect}.
         *
         * @param value whether the owner handles explosions
         * @return this
         * @since 1.438.0
         */
        public @NotNull Builder ownExplosions(boolean value) {
            this.ownExplosions = value;
            return this;
        }

        /**
         * Whether hoppers, droppers and hopper minecarts are kept from pulling
         * items out of the block or pushing items into it. Off by default. On,
         * a loot chest stays the loot chest's: nobody drains it from below.
         *
         * <p>The listener behind it is registered the first time any block asks
         * for it, because Paper skips building a move event for every hopper on
         * the server while nobody listens to one.
         *
         * @param value whether to guard its inventory
         * @return this
         * @since 1.458.0
         */
        public @NotNull Builder protectInventory(boolean value) {
            this.protectInventory = value;
            return this;
        }

        /**
         * Whether a click another plugin already cancelled is left alone. Off by
         * default: a crate at spawn opens even where a protection plugin denies
         * every interaction. On, a claim or a region that keeps the block shut
         * keeps it shut, and no handler runs. The library listens at
         * {@code HIGH}, so what is seen is what plugins at {@code NORMAL} and
         * below decided.
         *
         * @param value whether to skip cancelled clicks
         * @return this
         * @since 1.458.0
         */
        public @NotNull Builder ignoreCancelled(boolean value) {
            this.ignoreCancelled = value;
            return this;
        }

        /**
         * Registers it, replacing whatever held that location.
         *
         * @return the registration
         */
        public @NotNull ClickableBlock register() {
            ClickableBlock block = new ClickableBlock(plugin, location, onLeft, onRight, protectBlock, cancelVanilla,
                    ownExplosions, protectInventory, ignoreCancelled);
            BlockRuntime.register(block);
            return block;
        }
    }
}
