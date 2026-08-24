package net.exylia.lib.region.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.SelectionOptions;
import net.exylia.lib.region.SelectionResult;
import net.exylia.lib.region.SelectionSession;
import net.exylia.lib.region.SelectionState;
import net.exylia.lib.platform.Platform;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Shared owner-scoped selection registry and state machine.
 *
 * <h2>What a selection is, beyond two coordinates</h2>
 * A selector the player is actually handed, the box drawn while they pick it,
 * and a confirmation before it answers. All three were in ExyliaCommons and all
 * three are here; what is not here is commons' habit of overwriting the item in
 * the player's hand to deliver the first one.
 *
 * <h2>Whose scheduler runs it</h2>
 * The preview and the two inventory writes run on <em>ExyliaLib's</em>
 * scheduler, not the owning plugin's. A plugin is disabled before
 * {@code Regions.release} is called, and a dying plugin cannot schedule
 * anything — so scheduling the wand's return on its scheduler would leave the
 * admin holding a tool nobody would take back.
 */
@ApiStatus.Internal
public final class SelectionRuntime {

    private static final Object LOCK = new Object();
    private static final Map<Key, Session> BY_OWNER_PLAYER = new HashMap<>();
    private static final Map<UUID, Session> BY_PLAYER = new HashMap<>();

    /** How the selector reaches a player. Replaced in tests. */
    private static volatile SelectorWand wand = SelectorWand.BUKKIT;

    private SelectionRuntime() {
    }


    /** Test seam: how the selector reaches a player. */
    static void installWand(@NotNull SelectorWand replacement) {
        wand = Objects.requireNonNull(replacement, "replacement");
    }

    /** Test seam: restores the real inventory writes. */
    static void resetWand() {
        wand = SelectorWand.BUKKIT;
    }

    /**
     * Starts a session while enforcing one globally active selector per player.
     *
     * @param plugin exact owning plugin
     * @param player the selecting player
     * @param options immutable selection options
     * @return new active session
     * @throws IllegalStateException if any plugin already has an active selector for the player
     */
    public static @NotNull SelectionSession begin(@NotNull Plugin plugin,
                                                   @NotNull Player player,
                                                   @NotNull SelectionOptions options) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(options, "options");
        UUID playerId = player.getUniqueId();
        Session session;
        synchronized (LOCK) {
            Session active = BY_PLAYER.get(playerId);
            if (active != null) {
                throw new IllegalStateException("Player already has an active selection owned by "
                        + active.owner());
            }
            Key key = new Key(plugin.getName(), playerId);
            session = new Session(key, plugin, options);
            BY_OWNER_PLAYER.put(key, session);
            BY_PLAYER.put(playerId, session);
        }
        session.equip(player);
        return session;
    }

    /** Returns one owner's active session for a player, if present. */
    public static @NotNull Optional<SelectionSession> selection(@NotNull String owner,
                                                                 @NotNull UUID playerId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(playerId, "playerId");
        synchronized (LOCK) {
            return Optional.ofNullable(BY_OWNER_PLAYER.get(new Key(owner, playerId)));
        }
    }

    /** Cancels one owner's active session for a player. */
    public static boolean cancel(@NotNull String owner, @NotNull UUID playerId) {
        Session session;
        synchronized (LOCK) {
            session = BY_OWNER_PLAYER.get(new Key(Objects.requireNonNull(owner, "owner"),
                    Objects.requireNonNull(playerId, "playerId")));
        }
        return session != null && session.cancel();
    }

    /** Returns the uniquely routed active session for a player. */
    static @Nullable Session routed(@NotNull UUID playerId) {
        synchronized (LOCK) {
            return BY_PLAYER.get(Objects.requireNonNull(playerId, "playerId"));
        }
    }

    /** Applies an exact block corner to the globally routed session. */
    public static boolean select(@NotNull UUID playerId, boolean first,
                                 @NotNull BlockPosition position) {
        Session session = routed(playerId);
        return session != null && session.select(first, position);
    }

    /**
     * Accepts the corners the routed session is holding.
     *
     * @param playerId who confirmed
     * @return whether a session was waiting for exactly this
     */
    public static boolean confirm(@NotNull UUID playerId) {
        Session session = routed(playerId);
        return session != null && session.confirm();
    }

    /** Cancels all active sessions for the exact case-sensitive owner name. */
    public static int release(@NotNull String owner) {
        Objects.requireNonNull(owner, "owner");
        Session[] sessions;
        synchronized (LOCK) {
            sessions = BY_OWNER_PLAYER.entrySet().stream()
                    .filter(entry -> entry.getKey().owner().equals(owner))
                    .map(Map.Entry::getValue)
                    .toArray(Session[]::new);
        }
        int cancelled = 0;
        for (Session session : sessions) {
            if (session.cancel()) cancelled++;
        }
        return cancelled;
    }

    /** Cancels every active selection session. */
    public static void releaseAll() {
        Session[] sessions;
        synchronized (LOCK) {
            sessions = BY_PLAYER.values().toArray(Session[]::new);
        }
        for (Session session : sessions) session.cancel();
    }

    private static void remove(Session session) {
        synchronized (LOCK) {
            BY_OWNER_PLAYER.remove(session.key, session);
            BY_PLAYER.remove(session.playerId(), session);
        }
    }

    private record Key(String owner, UUID playerId) {
        private Key {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(playerId, "playerId");
        }
    }

    /** Internal mutable state behind the immutable public session view. */
    static final class Session implements SelectionSession {
        private final Key key;
        private final Plugin plugin;
        private final SelectionOptions options;
        private final CompletableFuture<SelectionResult> completion = new CompletableFuture<>();
        private final CompletionStage<SelectionResult> exposed = completion.minimalCompletionStage();

        private SelectionState state = SelectionState.ACTIVE;
        private BlockPosition first;
        private BlockPosition second;
        private volatile SelectionPreview preview;
        private volatile boolean equipped;

        private Session(Key key, Plugin plugin, SelectionOptions options) {
            this.key = key;
            this.plugin = plugin;
            this.options = options;
        }

        @Override
        public @NotNull UUID playerId() {
            return key.playerId();
        }

        @Override
        public @NotNull String owner() {
            return key.owner();
        }

        @Override
        public synchronized @NotNull SelectionState state() {
            return state;
        }

        @Override
        public synchronized @NotNull Optional<BlockPosition> first() {
            return Optional.ofNullable(first);
        }

        @Override
        public synchronized @NotNull Optional<BlockPosition> second() {
            return Optional.ofNullable(second);
        }

        @Override
        public @NotNull CompletionStage<SelectionResult> result() {
            return exposed;
        }

        @NotNull SelectionOptions options() {
            return options;
        }

        // ------------------------------------------------------------- the tool

        /**
         * Hands over the selector, if this session is meant to.
         *
         * <p>Nothing here may throw: the session is already registered by the
         * time it runs, and an exception on the way out would leave the player
         * holding a selector nobody owns and unable to start another selection
         * with any plugin.
         */
        private void equip(Player player) {
            if (!options.giveSelector()) {
                return;
            }
            onPlayerThread(player, () -> {
                ItemStack item;
                try {
                    item = wand.build(plugin, options);
                } catch (RuntimeException | LinkageError unbuildable) {
                    // A selection nobody could be handed a tool for is still a
                    // selection: the material check is what selects, and losing
                    // the whole session over an item would strand the player's
                    // selector for every plugin until they reconnected.
                    //
                    // LinkageError and not only RuntimeException: building an
                    // ItemStack resolves the item registry, which a server that
                    // is still starting — or a test — does not have, and that
                    // arrives as an Error rather than an exception.
                    Debug.of(plugin).error("Could not build the region selector for "
                            + player.getName() + '.', unbuildable);
                    return;
                }
                int slot;
                try {
                    slot = wand.give(player, item);
                } catch (RuntimeException | LinkageError unwritable) {
                    Debug.of(plugin).error("Could not hand the region selector to "
                            + player.getName() + '.', unwritable);
                    return;
                }
                if (slot == SelectorWand.NO_ROOM) {
                    // Not a failure: the material check is what selects, so an
                    // admin with their own axe carries on. Saying nothing would
                    // leave them clicking with an empty hand wondering why.
                    say(player, "{warning}● {letters}No room for the selector — hold a {highlight}"
                            + options.selectorMaterial().name() + " {letters}instead");
                    return;
                }
                equipped = true;
            });
        }

        /** Takes it back, however this session ended. */
        private void unequip() {
            if (!equipped) {
                return;
            }
            equipped = false;
            Player player = Bukkit.getPlayer(playerId());
            if (player == null || !player.isOnline()) {
                // Left the server holding it. Nothing to write to, and the item
                // is inert: without a session, a click with it is a click.
                return;
            }
            onPlayerThread(player, () -> wand.take(player, plugin));
        }

        // ------------------------------------------------------------- corners

        private boolean select(boolean selectingFirst, BlockPosition position) {
            SelectionResult completed = null;
            boolean bothSet;
            synchronized (this) {
                if (state != SelectionState.ACTIVE && state != SelectionState.AWAITING_CONFIRMATION) {
                    return false;
                }
                if (selectingFirst) {
                    first = position;
                } else {
                    second = position;
                }
                bothSet = first != null && second != null
                        && (!options.requireSameWorld() || first.world().equals(second.world()));
                if (bothSet && options.requireConfirmation()) {
                    // Whichever corner was just moved, the box is now complete
                    // and the player should see it and be asked about it.
                    state = SelectionState.AWAITING_CONFIRMATION;
                } else if (bothSet && !selectingFirst) {
                    state = SelectionState.COMPLETED;
                    completed = new SelectionResult(first.world(), first, second);
                } else {
                    // Without a confirmation only the second corner can end it:
                    // a left click means "start here", and answering on one
                    // would end the selection the moment somebody corrected the
                    // corner they had already placed.
                    state = SelectionState.ACTIVE;
                }
            }

            // Asked for only when there is something to say or draw: a
            // selection a plugin drives silently must not need a live server at
            // all, which is what lets the whole state machine be tested.
            Player player = watching();
            if (player != null) {
                announce(player, selectingFirst, position, bothSet);
                redraw(player);
            }
            if (!bothSet && first != null && second != null && options.requireSameWorld()) {
                Debug.of(plugin).debug("Region selection corners for " + playerId()
                        + " are in different worlds; selection remains active.");
            }
            if (completed != null) {
                finish(completed);
            }
            return true;
        }

        /** Accepts what is on screen. */
        @Override
        public boolean confirm() {
            SelectionResult completed;
            synchronized (this) {
                if (state != SelectionState.AWAITING_CONFIRMATION || first == null || second == null) {
                    return false;
                }
                state = SelectionState.COMPLETED;
                completed = new SelectionResult(first.world(), first, second);
            }
            if (options.feedback()) {
                Player player = Bukkit.getPlayer(playerId());
                if (player != null) {
                    say(player, "{success}● {letters}Selection confirmed");
                }
            }
            finish(completed);
            return true;
        }

        private void finish(SelectionResult completed) {
            remove(Session.this);
            releaseHeld();
            completion.complete(completed);
        }

        // -------------------------------------------------------- what is seen

        private void announce(Player player, boolean selectingFirst, BlockPosition position,
                              boolean bothSet) {
            if (!options.feedback()) {
                return;
            }
            String corner = selectingFirst
                    ? "{success}● {letters}First corner {letters_black}» {info}"
                    : "{error}● {letters}Second corner {letters_black}» {info}";
            say(player, corner + position.x() + ", " + position.y() + ", " + position.z());
            if (!bothSet) {
                return;
            }
            say(player, "{secondary}Selection: {info}" + volume() + " {letters}blocks");
            if (options.requireConfirmation()) {
                Effects.actionBar("{warning}➥ Shift + left-click to confirm").show(player);
            }
        }

        /** How many blocks the two corners enclose, both ends included. */
        private long volume() {
            Cuboid cuboid = shape() instanceof Cuboid box ? box : null;
            if (cuboid == null) {
                return 0L;
            }
            return Math.round((cuboid.maxX() - cuboid.minX())
                    * (cuboid.maxY() - cuboid.minY())
                    * (cuboid.maxZ() - cuboid.minZ()));
        }

        /**
         * What the preview draws right now.
         *
         * <p>One corner is the block it is: an admin who clicked the wrong block
         * should see that before walking to the far end.
         */
        private synchronized @Nullable RegionShape shape() {
            // Both corners only make a box when they share a world. An owner
            // who allowed two worlds gets the first corner drawn rather than an
            // exception from a cuboid that cannot exist.
            if (first != null && second != null && first.world().equals(second.world())) {
                return Cuboid.blocks(first, second);
            }
            BlockPosition only = first != null ? first : second;
            return only == null ? null : Cuboid.block(only);
        }

        private synchronized @Nullable UUID shapeWorld() {
            BlockPosition anchor = first != null ? first : second;
            return anchor == null ? null : anchor.world().id();
        }

        private void redraw(Player player) {
            if (!options.hasPreview() || preview != null) {
                return;
            }
            UUID worldId = shapeWorld();
            if (worldId == null) {
                return;
            }
            Plugin scheduler = RegionRuntime.library();
            if (scheduler == null) {
                return;
            }
            preview = SelectionPreview.start(scheduler, player, worldId, this::shape, options);
        }

        /**
         * Gives back everything this session took, before anybody is told it
         * ended.
         *
         * <p>The order is the whole method. The session is already out of the
         * registry, so a callback that starts a new selection cannot have its
         * tool taken by this one; and nothing here may throw its way out,
         * because a failed inventory write that escaped would leave the future
         * uncompleted and the player unable to select for any plugin until they
         * reconnected. That is exactly how the first version of this broke.
         */
        private void releaseHeld() {
            try {
                stopPreview();
                unequip();
            } catch (RuntimeException | LinkageError broken) {
                Debug.of(plugin).error("Could not give back the region selector of "
                        + playerId() + '.', broken);
            }
        }

        private void stopPreview() {
            SelectionPreview running = preview;
            preview = null;
            if (running != null) {
                running.stop();
            }
        }

        // ------------------------------------------------------------- ending

        @Override
        public boolean cancel() {
            synchronized (this) {
                if (state != SelectionState.ACTIVE && state != SelectionState.AWAITING_CONFIRMATION) {
                    return false;
                }
                state = SelectionState.CANCELLED;
            }
            remove(this);
            releaseHeld();
            completion.completeExceptionally(new CancellationException("Selection cancelled"));
            return true;
        }

        @Override
        public void close() {
            cancel();
        }

        /** Whoever is being told or shown something, or {@code null} for neither. */
        private @Nullable Player watching() {
            return options.feedback() || options.hasPreview()
                    ? Bukkit.getPlayer(playerId())
                    : null;
        }

        private void say(Player player, String message) {
            Text.from(plugin, message).forPlayer(player).send(player);
        }

        /**
         * Runs an inventory write where the player's inventory may be written.
         *
         * <p>ExyliaLib's scheduler, deliberately: the owning plugin is already
         * disabled by the time its selections are released, and a disabled
         * plugin cannot schedule the return of its own tool.
         */
        private void onPlayerThread(Player player, Runnable work) {
            // On Spigot and Paper the caller is already there: a selection is
            // started from a menu click or a command. Running inline means the
            // player has the tool the moment they are told to select rather
            // than a tick later, and a tick is long enough to click and wonder
            // why nothing happened.
            if (!Platform.isFolia() && Bukkit.isPrimaryThread()) {
                work.run();
                return;
            }
            Plugin scheduler = RegionRuntime.library();
            if (scheduler == null) {
                work.run();
                return;
            }
            Tasks.of(scheduler).runAtEntity(player, work);
        }
    }
}
