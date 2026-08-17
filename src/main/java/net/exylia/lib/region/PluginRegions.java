package net.exylia.lib.region;

import net.exylia.lib.region.internal.RegionRuntime;
import net.exylia.lib.region.internal.SelectionRuntime;
import net.exylia.lib.region.internal.VisualizationRuntime;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
     * Begins a block selection using {@link SelectionOptions#DEFAULT}.
     *
     * <p>Routing is globally exclusive per player. Starting while any plugin owns an active
     * selector for the player fails rather than allowing one click to mutate two sessions.
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
     * @param options selector material and interaction rules
     * @return active owner-scoped selection session
     * @throws IllegalStateException if the player already has an active selection
     */
    public @NotNull SelectionSession beginSelection(@NotNull Player player,
                                                     @NotNull SelectionOptions options) {
        Objects.requireNonNull(player, "player");
        return SelectionRuntime.begin(plugin, player.getUniqueId(), options);
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
