package net.exylia.lib.region;

import net.exylia.lib.region.internal.PlacedBlockRuntime;
import net.exylia.lib.region.internal.RegionEntities;
import net.exylia.lib.region.internal.RegionRuntime;
import net.exylia.lib.region.internal.SelectionRuntime;
import net.exylia.lib.region.internal.VisualizationRuntime;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Owner-scoped access to the shared region runtime for one exact plugin instance. */
public final class PluginRegions {

    private final Plugin plugin;
    private final String owner;
    private final String namespace;

    PluginRegions(Plugin plugin, String namespace) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.owner = plugin.getName();
        this.namespace = new RegionId(namespace, "validation").namespace();
    }

    public @NotNull Plugin plugin() {
        return plugin;
    }

    public @NotNull String owner() {
        return owner;
    }

    public @NotNull String namespace() {
        return namespace;
    }

    public @NotNull RegionId id(@NotNull String key) {
        return new RegionId(namespace, key);
    }

    public @NotNull RegionSnapshot region(@NotNull String key, @NotNull WorldIdentity world,
                                           @NotNull RegionShape shape, int priority,
                                           @NotNull PolicySet policies) {
        return new RegionSnapshot(id(key), owner, world, shape, priority, policies);
    }

    public @NotNull RegionSnapshot register(@NotNull RegionSnapshot region) {
        validate(region);
        return RegionRuntime.register(plugin, region);
    }

    public boolean unregister(@NotNull RegionId id) {
        validate(id);
        RegionSnapshot existing = RegionRuntime.get(id);
        if (existing != null && !existing.owner().equals(owner)) {
            throw new IllegalArgumentException("Region belongs to another plugin: " + id);
        }
        return RegionRuntime.unregister(plugin, id) != null;
    }

    public boolean unregister(@NotNull String key) {
        return unregister(id(key));
    }

    public @NotNull RegionSnapshot replace(@NotNull RegionSnapshot region) {
        validate(region);
        RegionSnapshot existing = RegionRuntime.get(region.id());
        if (existing != null && !existing.owner().equals(owner)) {
            throw new IllegalArgumentException("Region belongs to another plugin: " + region.id());
        }
        return RegionRuntime.replace(plugin, region);
    }

    /** Atomically replaces exactly this owner while preserving every other plugin's regions. */
    public void replaceAll(@NotNull Collection<RegionSnapshot> regions) {
        List<RegionSnapshot> copy = List.copyOf(Objects.requireNonNull(regions, "regions"));
        copy.forEach(this::validate);
        RegionRuntime.replaceAll(plugin, copy);
    }

    public @NotNull Optional<RegionSnapshot> get(@NotNull RegionId id) {
        validate(id);
        RegionSnapshot region = RegionRuntime.get(id);
        if (region == null) return Optional.empty();
        if (!region.owner().equals(owner)) {
            throw new IllegalArgumentException("Region belongs to another plugin: " + id);
        }
        return Optional.of(region);
    }

    public @NotNull Optional<RegionSnapshot> get(@NotNull String key) {
        return get(id(key));
    }

    public @NotNull List<RegionSnapshot> all() {
        return RegionRuntime.owner(owner);
    }

    public @NotNull List<RegionSnapshot> at(@NotNull Location location) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        return at(world.getUID(), location.getX(), location.getY(), location.getZ());
    }

    public @NotNull List<RegionSnapshot> at(@NotNull UUID worldId,
                                             double x, double y, double z) {
        return RegionRuntime.queryOwner(owner, worldId, x, y, z);
    }

    /** Resolves only this plugin owner's regions, unlike the global {@link Regions#resolve}. */
    public <T> @NotNull PolicyResolution<T> resolve(@NotNull Location location,
                                                    @NotNull PolicyKey<T> key) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        return resolve(world.getUID(), location.getX(), location.getY(), location.getZ(), key);
    }

    /** Resolves only this plugin owner's regions at a primitive point. */
    public <T> @NotNull PolicyResolution<T> resolve(@NotNull UUID worldId,
                                                     double x, double y, double z,
                                                     @NotNull PolicyKey<T> key) {
        return RegionRuntime.resolveOwner(owner, worldId, x, y, z, key);
    }

    /**
     * Whether a player put this block here, inside one of this plugin's regions.
     *
     * <p>The library records the block a player places in any region declaring
     * {@code player_build_only} or {@code temporary_blocks}, and forgets it when the
     * block is broken or the region goes away. This is the question that record
     * answers; cancelling the break is still this plugin's call, exactly as with
     * every other policy.
     *
     * <pre>{@code
     * if (regions.resolve(location, CommonRegionPolicies.PLAYER_BUILD_ONLY).value()
     *         && !regions.placedByPlayer(event.getBlock())) {
     *     event.setCancelled(true);
     * }
     * }</pre>
     *
     * <p>{@code false} for a region that declares neither policy: nothing was recorded
     * there, so nothing can be attributed.
     *
     * @param block block to attribute
     * @return {@code true} when a player placed it inside one of this plugin's regions
     */
    public boolean placedByPlayer(@NotNull Block block) {
        Objects.requireNonNull(block, "block");
        return placedByPlayer(block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ());
    }

    /** Whether a player placed the block containing a location. */
    public boolean placedByPlayer(@NotNull Location location) {
        Objects.requireNonNull(location, "location");
        World world = Objects.requireNonNull(location.getWorld(), "location world");
        return placedByPlayer(world.getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /** Whether a player placed the block at a primitive position. */
    public boolean placedByPlayer(@NotNull UUID worldId, int x, int y, int z) {
        List<RegionSnapshot> regions = RegionRuntime.queryOwner(owner,
                Objects.requireNonNull(worldId, "worldId"), x, y, z);
        for (int index = 0; index < regions.size(); index++) {
            if (PlacedBlockRuntime.tracked(regions.get(index).id(), x, y, z)) return true;
        }
        return false;
    }

    /**
     * Removes the loose entities a region contains.
     *
     * <p>Loose means what a round leaves behind: dropped items, experience
     * orbs, projectiles, minecarts, end crystals and fireworks. Armour stands,
     * item frames, paintings and mobs are left alone, because a decorated
     * region cleared between rounds would lose its decoration once and never
     * say so. Pass a predicate to widen or narrow that.
     *
     * <p>Membership is the shape's, not its bounding box's: a spherical region
     * does not clear the corners of the cube around it. Players are never
     * removed.
     *
     * <pre>{@code
     * regions.get("arena").ifPresent(regions::clearEntities);
     * }</pre>
     *
     * <p>Ownership is not checked. The region is read as geometry, and the
     * caller already holds the snapshot.
     *
     * <h2>Threading</h2>
     * A world read, so it runs on the thread that owns the region — hop through
     * {@code Tasks.of(plugin).runAtLocation(...)} first. Returns {@code 0} when
     * the region's world is not loaded.
     *
     * @param region the region to clear
     * @return how many entities were removed
     * @since 1.58.0
     */
    public int clearEntities(@NotNull RegionSnapshot region) {
        return clearEntities(region, RegionEntities::loose);
    }

    /**
     * Removes the entities a region contains that a predicate accepts.
     *
     * <p>The predicate sees every non-player entity inside the shape, so it
     * decides alone: {@code entity -> true} clears everything a player is not.
     *
     * <h2>Threading</h2>
     * A world read, so it runs on the thread that owns the region — hop through
     * {@code Tasks.of(plugin).runAtLocation(...)} first. Returns {@code 0} when
     * the region's world is not loaded.
     *
     * @param region the region to clear
     * @param which  what to remove among the entities inside
     * @return how many entities were removed
     * @since 1.58.0
     */
    public int clearEntities(@NotNull RegionSnapshot region, @NotNull Predicate<Entity> which) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(which, "which");
        World world = Bukkit.getWorld(region.worldId());
        if (world == null) {
            return 0;
        }
        return RegionEntities.clear(world, region.shape(), which);
    }

    /**
     * Begins a block selection using {@link SelectionOptions#DEFAULT}.
     *
     * <p>Routing is globally exclusive per player. Starting while any plugin owns an active
     * selector for the player fails rather than allowing one click to mutate two sessions.
     *
     * <p>Hands the player a golden axe, draws the box while they pick it, and
     * waits for a shift + left-click before answering. Every one of those is a
     * switch on {@link SelectionOptions}.
     *
     * @param player player whose UUID is selected; the player object is not retained
     * @return active owner-scoped selection session
     * @throws IllegalStateException if the player already has an active selection
     */
    public @NotNull SelectionSession beginSelection(@NotNull Player player) {
        return beginSelection(player, SelectionOptions.DEFAULT);
    }

    /**
     * Begins a block selection with immutable options.
     *
     * @param player player whose UUID is selected; the player object is not retained
     * @param options the selector, the preview and the confirmation rules
     * @return active owner-scoped selection session
     * @throws IllegalStateException if the player already has an active selection
     */
    public @NotNull SelectionSession beginSelection(@NotNull Player player,
                                                     @NotNull SelectionOptions options) {
        Objects.requireNonNull(player, "player");
        return SelectionRuntime.begin(plugin, player, options);
    }

    /**
     * Returns this owner's active selection for a player.
     *
     * @param player player to inspect
     * @return active selection, if present
     */
    public @NotNull Optional<SelectionSession> selection(@NotNull Player player) {
        Objects.requireNonNull(player, "player");
        return selection(player.getUniqueId());
    }

    /**
     * Returns this owner's active selection for a player UUID.
     *
     * @param playerId player UUID
     * @return active selection, if present
     */
    public @NotNull Optional<SelectionSession> selection(@NotNull UUID playerId) {
        return SelectionRuntime.selection(owner, playerId);
    }

    /**
     * Cancels this owner's active selection for a player.
     *
     * @param player player whose selection is cancelled
     * @return {@code true} when an active session was cancelled
     */
    public boolean cancelSelection(@NotNull Player player) {
        Objects.requireNonNull(player, "player");
        return cancelSelection(player.getUniqueId());
    }

    /**
     * Cancels this owner's active selection for a player UUID.
     *
     * @param playerId player UUID
     * @return {@code true} when an active session was cancelled
     */
    public boolean cancelSelection(@NotNull UUID playerId) {
        return SelectionRuntime.cancel(owner, playerId);
    }

    /**
     * Shows a region outline to one player with default visualization settings.
     *
     * @param player viewer receiving the particles
     * @param id registered region identifier in this facade's namespace
     * @return cancellable visualization handle
     * @throws IllegalArgumentException if the region is absent or belongs to another plugin
     */
    public @NotNull RegionVisualization visualize(@NotNull Player player, @NotNull RegionId id) {
        return visualize(player, id, VisualizationOptions.defaults());
    }

    /** Shows a region outline addressed by this facade's local key with default settings. */
    public @NotNull RegionVisualization visualize(@NotNull Player player, @NotNull String key) {
        return visualize(player, id(key), VisualizationOptions.defaults());
    }

    /** Shows a region outline addressed by this facade's local key. */
    public @NotNull RegionVisualization visualize(@NotNull Player player, @NotNull String key,
                                                   @NotNull VisualizationOptions options) {
        return visualize(player, id(key), options);
    }

    /**
     * Shows a region outline to one player.
     *
     * <p>The current snapshot is resolved again on every frame, so replacing the registered
     * shape or world immediately changes an existing visualization.
     *
     * @param player viewer receiving the particles
     * @param id registered region identifier in this facade's namespace
     * @param options immutable render settings
     * @return cancellable visualization handle
     * @throws IllegalArgumentException if the region is absent or belongs to another plugin
     */
    public @NotNull RegionVisualization visualize(@NotNull Player player, @NotNull RegionId id,
                                                   @NotNull VisualizationOptions options) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(options, "options");
        validate(id);
        RegionSnapshot region = RegionRuntime.get(id);
        if (region == null) {
            throw new IllegalArgumentException("Region is not registered: " + id);
        }
        if (!region.owner().equals(owner)) {
            throw new IllegalArgumentException("Region belongs to another plugin: " + id);
        }
        return VisualizationRuntime.start(plugin, player, id, options);
    }

    private void validate(RegionSnapshot region) {
        Objects.requireNonNull(region, "region");
        validate(region.id());
        if (!region.owner().equals(owner)) {
            throw new IllegalArgumentException("Region owner must be exactly " + owner);
        }
    }

    private void validate(RegionId id) {
        Objects.requireNonNull(id, "id");
        if (!id.namespace().equals(namespace)) {
            throw new IllegalArgumentException("Region namespace must be " + namespace);
        }
    }
}
