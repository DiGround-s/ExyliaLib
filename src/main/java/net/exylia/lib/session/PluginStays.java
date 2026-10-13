package net.exylia.lib.session;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.snapshot.PluginSnapshots;
import net.exylia.lib.util.snapshot.Snapshots;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * One plugin's way to keep players somewhere and give them back; see {@link Stays}.
 *
 * <p>Every method is safe from any thread. Work that touches the player runs on
 * the player's own thread, which is why the answers are futures.
 *
 * @since 1.292.0
 */
public final class PluginStays {

    /** The snapshot context every stay of a plugin is saved under. */
    static final String CONTEXT = "stay";

    private final Plugin plugin;
    private final PluginSessions sessions;
    private final Backend backend;
    /** The snapshot save of each stay; a restore never starts before its save has landed. */
    private final Map<UUID, CompletableFuture<Void>> saves = new ConcurrentHashMap<>();
    /** Players on their way out, still protected after their claim may be gone. */
    private final Set<UUID> leaving = ConcurrentHashMap.newKeySet();

    PluginStays(@NotNull Plugin plugin) {
        this(plugin, Sessions.of(plugin), new LiveBackend(plugin));
    }

    /** The seam the tests drive: the same rules over a fake snapshot store and teleport. */
    PluginStays(@Nullable Plugin plugin, @NotNull PluginSessions sessions, @NotNull Backend backend) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.backend = backend;
    }

    /**
     * A player held by this plugin.
     *
     * @param claim the claim that holds them; {@link Claim#isCurrent()} turns false the moment
     *              they are let go, which is what every step after a teleport checks
     * @param ready completes once their snapshot is stored, the cue to move them
     */
    public record Stay(@NotNull Claim claim, @NotNull CompletableFuture<Void> ready) {

        /** Whether this stay still holds the player. */
        public boolean isCurrent() {
            return claim.isCurrent();
        }
    }

    /**
     * Takes the player and saves what they own.
     *
     * <p>Nothing is taken from them yet: they keep their things until they stand
     * where they are going, and {@link #dress} empties them there. A chest they
     * had open is closed when this runs on their thread, which hands anything on
     * the cursor or in the crafting grid back to the inventory, so the snapshot
     * holds it.
     *
     * @param player    the player
     * @param kind      what they will be doing, as {@link Sessions} reports it to other plugins
     * @param onRelease how to give them back when another plugin asks; must end in {@link #leave}
     * @return the stay, or empty when another plugin, or another stay of this one, has them
     */
    public @NotNull Optional<Stay> enter(@NotNull Player player, @NotNull String kind,
                                         @Nullable BooleanSupplier onRelease) {
        UUID uuid = player.getUniqueId();
        if (sessions.mine(uuid).isPresent() || leaving.contains(uuid)) {
            return Optional.empty();
        }
        return sessions.claim(uuid, kind, onRelease).map(claim -> keep(player, claim));
    }

    /**
     * Turns a claim this plugin already holds into a stay, and saves what the player owns.
     *
     * <p>For a hold that comes before the stay: a queue moving its player into
     * the match, or a player another plugin lent this one. The claim keeps its
     * token and its release handler. The same as {@link #enter} otherwise; the
     * caller makes sure the claim is not already in a stay.
     *
     * @param player the player
     * @param claim  this plugin's current claim on them
     * @return the stay, or empty when the claim is no longer current or the player is still leaving
     * @since 1.294.0
     */
    public @NotNull Optional<Stay> enter(@NotNull Player player, @NotNull Claim claim) {
        UUID uuid = player.getUniqueId();
        if (sessions.mine(uuid).filter(claim::equals).isEmpty() || leaving.contains(uuid)) {
            return Optional.empty();
        }
        return Optional.of(keep(player, claim));
    }

    private Stay keep(Player player, Claim claim) {
        UUID uuid = player.getUniqueId();
        // Closing hands whatever was on the cursor or in the crafting grid back
        // to the inventory, so the snapshot holds it. A screen only closes on
        // its owner's thread.
        if (backend.owns(player)) {
            backend.close(player);
        }
        CompletableFuture<Void> saved = backend.save(player);
        saves.put(uuid, saved);
        return new Stay(claim, saved);
    }

    /**
     * Empties the player and sets their game mode, once they have arrived.
     *
     * <p>The other half of {@link #enter}, on the far side of the teleport. The
     * snapshot holds the mode they had, so {@link #leave} hands it back.
     *
     * @param player the player
     * @param mode   the mode they play in
     * @param still  whether whoever moved them still wants them; a player who
     *               left during the teleport already has their things back, and
     *               emptying them now would take those away
     * @return completes once they are dressed, or were left alone
     */
    public @NotNull CompletableFuture<Void> dress(@NotNull Player player, @NotNull GameMode mode,
                                                  @NotNull BooleanSupplier still) {
        return backend.onEntity(player, () -> {
            if (still.getAsBoolean()) {
                backend.strip(player, mode);
            }
        });
    }

    /**
     * Sends the player back where they were when they entered, restores them
     * there, and releases them.
     *
     * @param player the player; one this plugin is not keeping is left alone
     * @return completes once they are home and free, so whatever wants them
     *         next asks after the release rather than during it
     */
    public @NotNull CompletableFuture<Void> leave(@NotNull Player player) {
        return depart(player, where -> backend.teleport(player, where));
    }

    /**
     * The same, to a place of the plugin's choosing rather than where they came from.
     *
     * @param player the player
     * @param home   where they go, a lobby or a spawn
     * @return completes once they are there and free
     */
    public @NotNull CompletableFuture<Void> leave(@NotNull Player player, @NotNull Location home) {
        return depart(player, ignored -> backend.teleport(player, home));
    }

    /**
     * Restores the player where they stand and releases them.
     *
     * <p>For a player who walked out on their own, to another world or through
     * a portal: sending them home would pull them back out of wherever they went.
     *
     * @param player the player
     * @return completes once they are restored and free
     */
    public @NotNull CompletableFuture<Void> leaveHere(@NotNull Player player) {
        return depart(player, null);
    }

    private CompletableFuture<Void> depart(Player player,
                                          @Nullable Function<Location, CompletableFuture<Void>> goHome) {
        UUID uuid = player.getUniqueId();
        Optional<Claim> claim = sessions.mine(uuid);
        CompletableFuture<Void> saved = saves.remove(uuid);
        if (claim.isEmpty() && saved == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (!leaving.add(uuid)) {
            // Already on the way out: the first leave finishes the job.
            return CompletableFuture.completedFuture(null);
        }
        // Nothing from here rides home on the cursor or in the grid.
        backend.onEntity(player, () -> backend.putAway(player));
        CompletableFuture<Void> landed = saved == null
                ? CompletableFuture.completedFuture(null)
                : saved.handle((ignored, failure) -> null);
        return landed
                .thenCompose(ignored -> backend.returnAndRestore(player,
                        goHome == null ? null : goHome::apply))
                // No snapshot to come home from: one a previous join already
                // used up. They are still standing here, so they still go home.
                .thenCompose(restored -> restored || goHome == null
                        ? CompletableFuture.<Void>completedFuture(null)
                        : goHome.apply(null))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        backend.error("Could not give " + player.getName() + " back", failure);
                    }
                    leaving.remove(uuid);
                    claim.ifPresent(Claim::release);
                });
    }

    /**
     * Gives back whatever a crash or a restart left behind, and the player with it.
     *
     * <p>Call on join. The oldest stored stay knows where they were before any
     * of it started, and that is where they are sent.
     *
     * @param player the player who joined
     * @return how many snapshots were put back
     */
    public @NotNull CompletableFuture<Integer> recover(@NotNull Player player) {
        return backend.returnAndRestoreAll(player, where -> backend.teleport(player, where))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        backend.error("Could not give " + player.getName() + " back after a restart", failure);
                    }
                });
    }

    /**
     * Whether this plugin answers for the player's belongings: holding them, or
     * still giving them back. Protection listeners guard on this.
     *
     * @param player the player
     * @return whether they are held or leaving
     */
    public boolean isHeld(@NotNull UUID player) {
        return leaving.contains(player) || sessions.holds(player);
    }

    /** The plugin these stays belong to. */
    @Nullable Plugin plugin() {
        return plugin;
    }

    /** What the rules need from the server, so the tests can stand in for it. */
    interface Backend {

        CompletableFuture<Void> save(Player player);

        CompletableFuture<Boolean> returnAndRestore(Player player,
                                                    @Nullable Function<Location, CompletableFuture<?>> goHome);

        CompletableFuture<Integer> returnAndRestoreAll(Player player,
                                                       Function<Location, CompletableFuture<?>> goHome);

        /** Moves the player; a {@code null} place is the main world's spawn. */
        CompletableFuture<Void> teleport(Player player, @Nullable Location where);

        /** Runs on the player's thread and completes after, or at once when they are gone. */
        CompletableFuture<Void> onEntity(Player player, Runnable work);

        boolean owns(Player player);

        void close(Player player);

        void putAway(Player player);

        void strip(Player player, GameMode mode);

        void error(String message, Throwable failure);
    }

    /** The server itself. */
    private static final class LiveBackend implements Backend {

        private final Plugin plugin;
        private final PluginSnapshots snapshots;

        LiveBackend(Plugin plugin) {
            this.plugin = plugin;
            this.snapshots = Snapshots.of(plugin);
        }

        @Override
        public CompletableFuture<Void> save(Player player) {
            return snapshots.save(player, CONTEXT);
        }

        @Override
        public CompletableFuture<Boolean> returnAndRestore(Player player,
                                                           @Nullable Function<Location, CompletableFuture<?>> goHome) {
            return snapshots.returnAndRestore(player, CONTEXT, goHome);
        }

        @Override
        public CompletableFuture<Integer> returnAndRestoreAll(Player player,
                                                              Function<Location, CompletableFuture<?>> goHome) {
            return snapshots.returnAndRestoreAll(player, goHome);
        }

        @Override
        public CompletableFuture<Void> teleport(Player player, @Nullable Location where) {
            Location target = where != null ? where : Bukkit.getWorlds().get(0).getSpawnLocation();
            return Teleports.of(plugin).to(player, target).start().future().thenApply(result -> null);
        }

        @Override
        public CompletableFuture<Void> onEntity(Player player, Runnable work) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            Tasks.of(plugin).runAtEntity(player, () -> {
                try {
                    work.run();
                } finally {
                    done.complete(null);
                }
            }, () -> done.complete(null));
            return done;
        }

        @Override
        public boolean owns(Player player) {
            return Tasks.of(plugin).isOwnedBy(player);
        }

        @Override
        public void close(Player player) {
            player.closeInventory();
        }

        @Override
        public void putAway(Player player) {
            // Neither the cursor nor the crafting grid is a slot a snapshot or a
            // clear touches, so an item parked there outlives both.
            if (player.getOpenInventory().getTopInventory() instanceof CraftingInventory grid) {
                grid.clear();
            }
            player.setItemOnCursor(null);
            player.closeInventory();
        }

        @Override
        public void strip(Player player, GameMode mode) {
            putAway(player);
            player.getInventory().clear();
            player.setGameMode(mode);
        }

        @Override
        public void error(String message, Throwable failure) {
            Debug.of(plugin).error(message, failure);
        }
    }
}
