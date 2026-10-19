package net.exylia.lib.region.internal;

import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.Display;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.packet.Packets;
import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.Cylinder;
import net.exylia.lib.region.HorizontalBounds;
import net.exylia.lib.region.HorizontalCylinder;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.SelectionHeight;
import net.exylia.lib.region.SelectionMode;
import net.exylia.lib.region.SelectionOptions;
import net.exylia.lib.region.SelectionResult;
import net.exylia.lib.region.SelectionSession;
import net.exylia.lib.region.SelectionState;
import net.exylia.lib.region.Sphere;
import net.exylia.lib.region.VerticalBounds;
import net.exylia.lib.region.WorldIdentity;
import net.exylia.lib.platform.Platform;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.LibraryMessages;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * <h2>What a selection is, beyond a few coordinates</h2>
 * A selector the player is actually handed, the shape drawn while they pick it,
 * and a confirmation before it answers. All three were in ExyliaCommons and all
 * three are here; what is not here is commons' habit of overwriting the item in
 * the player's hand to deliver the first one.
 *
 * <h2>Two clicks, or an outline</h2>
 * A box, a circle and a sphere are two clicks, held as {@code first} and
 * {@code second} whatever they mean: the corners, or the centre and a block on
 * the edge. A polygon and a freehand outline are a list of points instead. The
 * listener only ever says "left", "right", "shift + left" or "shift + right";
 * what those mean is decided here, by the mode the session is in.
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

    /**
     * The most points an outline may have.
     *
     * <p>Well under what a {@link net.exylia.lib.region.Polygon} accepts, and
     * past anything a hand clicks; a freehand pen lifts itself when it gets
     * there.
     */
    static final int MAX_OUTLINE_POINTS = 256;

    /** How far the cursor has to move, in blocks, before a freehand pen puts down another point. */
    static final int FREEHAND_STEP = 2;

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
     * @throws UnsupportedOperationException if the options hand out a virtual selector and
     *         PacketEvents is not installed
     */
    public static @NotNull SelectionSession begin(@NotNull Plugin plugin,
                                                   @NotNull Player player,
                                                   @NotNull SelectionOptions options) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(options, "options");
        // Refused before anything is registered: falling back to a real item
        // would hand out exactly what the caller asked never to hand out.
        if (options.giveSelector() && options.virtualSelector() && !Packets.isAvailable()) {
            throw new UnsupportedOperationException("A virtual selector needs PacketEvents");
        }
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
        session.guide();
        session.startPreview(player);
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

    /**
     * Applies a click on a block to the globally routed session: a left click
     * when {@code first}, a right click otherwise.
     */
    public static boolean select(@NotNull UUID playerId, boolean first,
                                 @NotNull BlockPosition position) {
        Objects.requireNonNull(position, "position");
        return first ? primary(playerId, position) : secondary(playerId, position);
    }

    /**
     * A left click: a box's first corner, a circle's centre, a polygon's next
     * point, or a freehand pen put down or lifted.
     *
     * @param playerId who clicked
     * @param position the block clicked, or {@code null} for a click in the air
     * @return whether the session did something with it
     * @since 1.312.0
     */
    public static boolean primary(@NotNull UUID playerId, @Nullable BlockPosition position) {
        Session session = routed(playerId);
        return session != null && session.primary(position);
    }

    /**
     * A right click: a box's second corner, a circle's edge, or an outline's
     * last point taken back.
     *
     * @param playerId who clicked
     * @param position the block clicked, or {@code null} for a click in the air
     * @return whether the session did something with it
     * @since 1.312.0
     */
    public static boolean secondary(@NotNull UUID playerId, @Nullable BlockPosition position) {
        Session session = routed(playerId);
        return session != null && session.secondary(position);
    }

    /**
     * A shift + right-click: the next shape the options offer, starting over.
     *
     * @param playerId who clicked
     * @return whether the shape changed
     * @since 1.312.0
     */
    public static boolean nextMode(@NotNull UUID playerId) {
        Session session = routed(playerId);
        return session != null && session.nextMode();
    }

    /**
     * Accepts the shape the routed session is holding.
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
    static final class Session implements SelectionSession, SelectionPreview.Canvas {

        private final Key key;
        private final Plugin plugin;
        private final SelectionOptions options;
        private final CompletableFuture<SelectionResult> completion = new CompletableFuture<>();
        private final CompletionStage<SelectionResult> exposed = completion.minimalCompletionStage();

        private SelectionState state = SelectionState.ACTIVE;
        private SelectionMode mode;
        private BlockPosition first;
        private BlockPosition second;
        /** An outline's points, in order. Guarded by this. */
        private final List<BlockPosition> outline = new ArrayList<>();
        /** Whether a freehand pen is down. Guarded by this. */
        private boolean drawing;
        private volatile SelectionPreview preview;
        private volatile Display guidance;
        private volatile boolean equipped;

        private Session(Key key, Plugin plugin, SelectionOptions options) {
            this.key = key;
            this.plugin = plugin;
            this.options = options;
            this.mode = options.modes().get(0);
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
        public synchronized @NotNull SelectionMode mode() {
            return mode;
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
        public synchronized @NotNull List<BlockPosition> points() {
            return List.copyOf(outline);
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
            SelectionMode starting = mode();
            onPlayerThread(player, () -> {
                ItemStack item;
                try {
                    item = wand.build(plugin, options, starting);
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
                try {
                    if (options.virtualSelector()) {
                        // Thrown away is put down: the drawn selector is the
                        // only thing the player has to give back.
                        wand.overlay(player, item, this::cancel);
                    } else {
                        wand.give(player, item);
                    }
                } catch (RuntimeException | LinkageError unwritable) {
                    Debug.of(plugin).error("Could not hand the region selector to "
                            + player.getName() + '.', unwritable);
                    return;
                }
                equipped = true;
            });
        }

        /** Redraws the selector for the shape now being picked, so its lore describes the right clicks. */
        private void reequip(Player player, SelectionMode now) {
            if (!equipped) {
                return;
            }
            onPlayerThread(player, () -> {
                try {
                    wand.refresh(player, wand.build(plugin, options, now), this::cancel);
                } catch (RuntimeException | LinkageError unwritable) {
                    // The old lore is wrong, not dangerous: the clicks still work.
                    Debug.of(plugin).error("Could not redraw the region selector of "
                            + player.getName() + '.', unwritable);
                }
            });
        }

        /**
         * Sweeps the player's selection axes, however this session ended.
         *
         * <p>Runs whether or not this session handed one over: an axe left
         * behind by a crash, a restart or a plugin that went away is rubbish in
         * an admin's inventory, and the end of a selection is the one moment we
         * know for certain none of them is in use.
         */
        private void unequip() {
            if (!equipped && !options.giveSelector()) {
                return;
            }
            equipped = false;
            Player player = Bukkit.getPlayer(playerId());
            if (player == null || !player.isOnline()) {
                // Left the server holding it. Nothing to write to, and the item
                // is inert: without a session, a click with it is a click. A
                // drawn one is forgotten by the packet module when they leave.
                return;
            }
            onPlayerThread(player, () -> {
                if (options.virtualSelector()) {
                    wand.unoverlay(player);
                }
                wand.take(player);
            });
        }

        // -------------------------------------------------------------- clicks

        private boolean primary(@Nullable BlockPosition position) {
            SelectionMode now = mode();
            if (now == SelectionMode.FREEHAND) {
                return togglePen(position);
            }
            if (position == null) {
                return false;
            }
            return now == SelectionMode.POLYGON ? addPoint(position) : corner(true, position);
        }

        private boolean secondary(@Nullable BlockPosition position) {
            if (mode().isOutline()) {
                return undoPoint();
            }
            return position != null && corner(false, position);
        }

        /** A box's corner, or a circle's or sphere's centre or edge. */
        private boolean corner(boolean selectingFirst, BlockPosition position) {
            SelectionResult completed = null;
            boolean bothSet;
            synchronized (this) {
                if (!open()) {
                    return false;
                }
                if (selectingFirst) {
                    first = position;
                } else {
                    second = position;
                }
                // A circle or a ball across two worlds is nothing at all, so for
                // them the same world is a rule rather than an option.
                boolean sameWorldRequired = options.requireSameWorld() || mode != SelectionMode.CUBOID;
                bothSet = first != null && second != null
                        && (!sameWorldRequired || first.world().equals(second.world()));
                if (bothSet && options.requireConfirmation()) {
                    // Whichever click was just moved, the shape is now complete
                    // and the player should see it and be asked about it.
                    state = SelectionState.AWAITING_CONFIRMATION;
                } else if (bothSet && !selectingFirst) {
                    state = SelectionState.COMPLETED;
                    completed = resultOf();
                } else {
                    // Without a confirmation only the second click can end it:
                    // a left click means "start here", and answering on one
                    // would end the selection the moment somebody corrected the
                    // corner they had already placed.
                    state = SelectionState.ACTIVE;
                }
            }

            // Asked for only when there is something to say: a selection a
            // plugin drives silently must not need a live server at all, which
            // is what lets the whole state machine be tested.
            Player player = watching();
            if (player != null) {
                announceCorner(player, selectingFirst, position, bothSet);
                guide();
            }
            if (!bothSet && first != null && second != null) {
                Debug.of(plugin).debug("Region selection corners for " + playerId()
                        + " are in different worlds; selection remains active.");
            }
            if (completed != null) {
                finish(completed);
            }
            return true;
        }

        /** A polygon's next point. */
        private boolean addPoint(BlockPosition position) {
            int count;
            synchronized (this) {
                if (!open()) {
                    return false;
                }
                restartOutlineIn(position.world());
                if (outline.size() >= MAX_OUTLINE_POINTS) {
                    return false;
                }
                outline.add(position);
                count = outline.size();
                settleOutline();
            }
            Player player = watching();
            if (player != null) {
                say(player, at(LibraryMessages.get().selection().point(), position)
                        .replace("%points%", String.valueOf(count)));
                guide();
            }
            return true;
        }

        /** An outline's last point, taken back; also lifts a freehand pen. */
        private boolean undoPoint() {
            int count;
            synchronized (this) {
                if (!open() || outline.isEmpty()) {
                    return false;
                }
                count = outline.size();
                outline.remove(count - 1);
                drawing = false;
                settleOutline();
            }
            Player player = watching();
            if (player != null) {
                say(player, LibraryMessages.get().selection().pointRemoved()
                        .replace("%points%", String.valueOf(count)));
                guide();
            }
            return true;
        }

        /**
         * Puts a freehand pen down, or lifts it.
         *
         * <p>A pen put down on a block starts there; one put down in the air
         * starts at the first block the player looks at.
         */
        private boolean togglePen(@Nullable BlockPosition position) {
            synchronized (this) {
                if (!open()) {
                    return false;
                }
                if (drawing) {
                    drawing = false;
                } else {
                    drawing = true;
                    if (position != null) {
                        restartOutlineIn(position.world());
                        follow(position);
                    }
                }
                settleOutline();
            }
            guide();
            return true;
        }

        /**
         * The block a freehand pen is over, from the preview's frame.
         *
         * <p>Becomes a point once it is {@link #FREEHAND_STEP} blocks from the
         * last one, and a run of points in a straight line keeps only its ends.
         */
        @Override
        public void look(@NotNull BlockPosition cursor) {
            boolean lifted;
            synchronized (this) {
                if (!drawing || !open()) {
                    return;
                }
                restartOutlineIn(cursor.world());
                follow(cursor);
                lifted = !drawing;
            }
            if (lifted) {
                guide();
            }
        }

        /** Adds a freehand point when the pen has moved far enough. Under the lock. */
        private void follow(BlockPosition cursor) {
            int size = outline.size();
            if (size > 0) {
                BlockPosition last = outline.get(size - 1);
                long dx = cursor.x() - last.x();
                long dz = cursor.z() - last.z();
                if (dx * dx + dz * dz < (long) FREEHAND_STEP * FREEHAND_STEP) {
                    return;
                }
                if (size > 1 && straightOn(outline.get(size - 2), last, cursor)) {
                    // Still going the same way: the middle point says nothing.
                    outline.set(size - 1, cursor);
                    return;
                }
            }
            outline.add(cursor);
            if (outline.size() >= MAX_OUTLINE_POINTS) {
                drawing = false;
                settleOutline();
            }
        }

        /** Whether {@code next} carries on from {@code before} through {@code last} in a straight line. */
        private static boolean straightOn(BlockPosition before, BlockPosition last, BlockPosition next) {
            long ax = last.x() - before.x();
            long az = last.z() - before.z();
            long bx = next.x() - last.x();
            long bz = next.z() - last.z();
            return ax * bz - az * bx == 0L && ax * bx + az * bz > 0L;
        }

        /** Starts the outline over when a point lands in another world. Under the lock. */
        private void restartOutlineIn(WorldIdentity world) {
            if (!outline.isEmpty() && !outline.get(0).world().equals(world)) {
                outline.clear();
            }
        }

        /**
         * An outline waits to be confirmed from its third point while the pen
         * is up, and is still being picked otherwise. Under the lock.
         */
        private void settleOutline() {
            state = !drawing && outline.size() >= 3
                    ? SelectionState.AWAITING_CONFIRMATION
                    : SelectionState.ACTIVE;
        }

        /** The next shape the options offer, from scratch. */
        private boolean nextMode() {
            SelectionMode now;
            synchronized (this) {
                List<SelectionMode> modes = options.modes();
                if (!open() || modes.size() < 2) {
                    return false;
                }
                now = modes.get((modes.indexOf(mode) + 1) % modes.size());
                mode = now;
                first = null;
                second = null;
                outline.clear();
                drawing = false;
                state = SelectionState.ACTIVE;
            }
            // Only asked for when there is something to say or redraw, like
            // every other click: a silent selection needs no server.
            Player player = options.feedback() || equipped ? Bukkit.getPlayer(playerId()) : null;
            if (player != null) {
                if (options.feedback()) {
                    LibraryMessages.Selection lines = LibraryMessages.get().selection();
                    say(player, lines.mode().replace("%mode%", lines.modeName(now)));
                }
                guide();
                reequip(player, now);
            }
            return true;
        }

        /** Accepts what is on screen. */
        @Override
        public boolean confirm() {
            SelectionResult completed;
            synchronized (this) {
                if (state != SelectionState.AWAITING_CONFIRMATION) {
                    return false;
                }
                completed = resultOf();
                if (completed == null) {
                    return false;
                }
                state = SelectionState.COMPLETED;
            }
            if (options.feedback()) {
                Player player = Bukkit.getPlayer(playerId());
                if (player != null) {
                    say(player, LibraryMessages.get().selection().confirmed());
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

        private boolean open() {
            return state == SelectionState.ACTIVE || state == SelectionState.AWAITING_CONFIRMATION;
        }

        // ------------------------------------------------------------- result

        /**
         * What the player picked, as the result the caller is given. Under the
         * lock.
         *
         * <p>A box keeps the clicked corners, exactly as before shapes existed —
         * across two worlds too, when the options allow it. Every other shape
         * reports the corners of the box around it, so a caller that only reads
         * {@link SelectionResult#cuboid()} still gets every block it holds.
         */
        private @Nullable SelectionResult resultOf() {
            if (mode == SelectionMode.CUBOID) {
                if (first == null || second == null) {
                    return null;
                }
                return new SelectionResult(first.world(), first, second,
                        SelectionSketch.twoPointShape(mode, options.height(), first, second));
            }
            RegionShape shape = SelectionSketch.shapeOf(sketch());
            if (shape == null || (!mode.isOutline() && (first == null || second == null))) {
                return null;
            }
            List<BlockPosition> clicked = mode.isOutline() ? outline : List.of(first, second);
            WorldIdentity world = clicked.get(0).world();
            int lowest = Integer.MAX_VALUE;
            int highest = Integer.MIN_VALUE;
            for (BlockPosition point : clicked) {
                lowest = Math.min(lowest, point.y());
                highest = Math.max(highest, point.y());
            }
            HorizontalBounds horizontal = shape.horizontalBounds();
            Optional<VerticalBounds> vertical = shape.verticalBounds();
            if (vertical.isPresent()) {
                lowest = (int) Math.floor(vertical.get().minY());
                highest = (int) Math.ceil(vertical.get().maxY()) - 1;
            }
            return new SelectionResult(world,
                    new BlockPosition(world, (int) Math.floor(horizontal.minX()), lowest,
                            (int) Math.floor(horizontal.minZ())),
                    new BlockPosition(world, (int) Math.ceil(horizontal.maxX()) - 1, highest,
                            (int) Math.ceil(horizontal.maxZ()) - 1),
                    shape);
        }

        /** The selection as the geometry sees it. Under the lock. */
        private SelectionSketch.State sketch() {
            return new SelectionSketch.State(mode, options.height(), first, second, List.copyOf(outline), drawing);
        }

        // -------------------------------------------------------- what is seen

        private boolean fullHeight() {
            return options.height() == SelectionHeight.FULL;
        }

        private void announceCorner(Player player, boolean selectingFirst, BlockPosition position,
                                    boolean bothSet) {
            if (!options.feedback()) {
                return;
            }
            LibraryMessages.Selection lines = LibraryMessages.get().selection();
            SelectionMode now = mode();
            if (now == SelectionMode.CUBOID) {
                say(player, at(selectingFirst ? lines.firstCorner() : lines.secondCorner(), position));
            } else if (selectingFirst) {
                say(player, at(lines.center(), position));
            }
            if (!bothSet) {
                return;
            }
            RegionShape shape = shape();
            if (now != SelectionMode.CUBOID) {
                say(player, lines.radius().replace("%radius%", radiusOf(shape)));
            }
            say(player, (fullHeight() ? lines.area() : lines.volume())
                    .replace("%blocks%", String.valueOf(SelectionSketch.sizeOf(shape))));
        }

        /** A circle's or sphere's radius, as a player reads it. */
        private static String radiusOf(@Nullable RegionShape shape) {
            double radius = switch (shape) {
                case Cylinder cylinder -> cylinder.radius();
                case HorizontalCylinder cylinder -> cylinder.radius();
                case Sphere sphere -> sphere.radius();
                case null, default -> 0.0;
            };
            return String.format(Locale.ROOT, "%.1f", radius);
        }

        private static String at(String line, BlockPosition position) {
            return line.replace("%x%", String.valueOf(position.x()))
                    .replace("%y%", String.valueOf(position.y()))
                    .replace("%z%", String.valueOf(position.z()));
        }

        /**
         * Keeps what to do next on screen for as long as the selection is open.
         *
         * <p>An action bar rather than a chat line, and a standing one rather
         * than a two-second one: the instruction is only useful while the
         * player is still clicking, and a message that scrolls away leaves an
         * admin mid-selection with nothing on screen telling them the shape
         * they are looking at still has to be confirmed. That standing prompt
         * is what ExyliaCommons had and what the first port dropped.
         *
         * <p>The text is fixed once a bar is showing, so a change stops the old
         * one and shows the new.
         */
        private void guide() {
            if (!options.feedback()) {
                return;
            }
            Player player = Bukkit.getPlayer(playerId());
            if (player == null) {
                return;
            }
            stopGuidance();
            String text = guidanceText();
            Plugin scheduler = RegionRuntime.library();
            guidance = (scheduler == null
                    ? Effects.actionBar(text)
                    : Effects.of(scheduler).actionBar(text))
                    .permanent()
                    .show(player);
        }

        private String guidanceText() {
            SelectionState now;
            SelectionMode picking;
            boolean hasFirst;
            boolean hasSecond;
            int points;
            boolean penDown;
            synchronized (this) {
                now = state;
                picking = mode;
                hasFirst = first != null;
                hasSecond = second != null;
                points = outline.size();
                penDown = drawing;
            }
            LibraryMessages.Selection lines = LibraryMessages.get().selection();
            String line;
            if (now == SelectionState.AWAITING_CONFIRMATION) {
                line = (fullHeight() ? lines.guideConfirmArea() : lines.guideConfirm())
                        .replace("%blocks%", String.valueOf(size()));
            } else {
                line = switch (picking) {
                    case CUBOID -> hasFirst && !hasSecond ? lines.guideFirst()
                            : hasSecond && !hasFirst ? lines.guideSecond()
                            : lines.guideCorners();
                    case POLYGON -> lines.guidePoints().replace("%points%", String.valueOf(points));
                    case FREEHAND -> penDown ? lines.guideDrawing() : lines.guideFreehand();
                    case CIRCLE, SPHERE -> hasFirst && !hasSecond ? lines.guideRadius() : lines.guideCenter();
                };
            }
            if (options.modes().size() > 1) {
                line = lines.guideMode().replace("%mode%", lines.modeName(picking)).replace("%guide%", line);
            }
            return line.replace("%selector%", selectorLabel());
        }

        private void stopGuidance() {
            Display showing = guidance;
            guidance = null;
            if (showing != null) {
                showing.stop();
            }
        }

        /** The selector as a player would name it: {@code golden axe}, not {@code GOLDEN_AXE}. */
        private String selectorLabel() {
            return options.selectorMaterial().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }

        /** Blocks in the shape, or columns at full height, once it is complete. */
        private long size() {
            synchronized (this) {
                boolean complete = mode.isOutline()
                        ? outline.size() >= 3
                        : first != null && second != null && first.world().equals(second.world());
                if (!complete) {
                    return 0L;
                }
            }
            return SelectionSketch.sizeOf(shape());
        }

        // ---------------------------------------------------- the preview's view

        private void startPreview(Player player) {
            Plugin library = RegionRuntime.library();
            if (library == null) {
                return;
            }
            preview = SelectionPreview.start(library, player, this, options,
                    options.modes().contains(SelectionMode.FREEHAND));
        }

        @Override
        public synchronized @Nullable UUID world() {
            if (mode.isOutline()) {
                return outline.isEmpty() ? null : outline.get(0).world().id();
            }
            BlockPosition anchor = first != null ? first : second;
            return anchor == null ? null : anchor.world().id();
        }

        @Override
        public synchronized boolean wantsCursor() {
            return open() && sketch().wantsCursor();
        }

        @Override
        public synchronized @Nullable RegionShape shape() {
            return SelectionSketch.shapeOf(sketch());
        }

        @Override
        public @NotNull List<Line> lines(@Nullable BlockPosition cursor, double level) {
            SelectionSketch.State now;
            synchronized (this) {
                now = sketch();
            }
            BlockPosition aimed = cursor != null && now.wantsCursor() ? cursor : null;
            return SelectionSketch.lines(now, aimed, level);
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
                stopGuidance();
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
                if (!open()) {
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

        /** Whoever is being told something, or {@code null} when nothing is said. */
        private @Nullable Player watching() {
            return options.feedback() ? Bukkit.getPlayer(playerId()) : null;
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
