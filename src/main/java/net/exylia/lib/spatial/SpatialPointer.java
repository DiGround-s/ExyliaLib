package net.exylia.lib.spatial;

import net.exylia.lib.stage.StageLook;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.tuner.Tuner;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * One player's pointer on the in-world screens a plugin shows them: the {@link Hotspot}s they can
 * aim at, which one is under their crosshair, and what their clicks, wheel and sneaking do. Made by
 * {@link SpatialInput#attach}; the library polls the crosshair every two ticks on the player's thread
 * and captures every click, so a game view keeps no hover index, no button list and no listener.
 *
 * <pre>{@code
 * SpatialPointer pointer = SpatialInput.of(plugin).attach(player)
 *         .sounds(config.sounds().hover(), "")           // the game plays its own press sounds
 *         .onSneak(() -> leave(player))
 *         .onEnd(end -> forfeit(player));
 * pointer.add(Hotspot.of(hitButton).onPress(click -> hit()));
 * ...
 * pointer.clear();                                       // new buttons for the next move
 * pointer.detach();                                      // the round is over
 * }</pre>
 *
 * <p>Clicks on nothing go to {@link #onClick}; {@link #onAnyClick} hears every click first. The wheel
 * goes to the hovered hotspot when it scrolls, else to {@link #onScroll}. The newest pointer a player
 * has takes their input; when it is detached the one under it (a game they left the menu of) gets it
 * back. It lives on the player's thread, or on a region's after {@link #deliverOn}: polls, pickers and
 * every callback run there. Hotspots may be added and removed from any thread.
 *
 * <p>An {@linkplain SpatialInput#observe observing} pointer is for a public board passers-by aim at:
 * it consumes (cancels) only the clicks that land on one of its hotspots, leaves the hotbar alone, and
 * hears nothing while the player has an attached pointer.
 *
 * @since 1.406.0
 */
public final class SpatialPointer {

    /** Why a pointer stopped. */
    public enum End {
        /** {@link #detach()} was called. */
        DETACHED,
        /** The plugin attached another one to the same player. */
        REPLACED,
        QUIT,
        DEATH,
        /** The player changed worlds: the screen stayed in the old one. */
        WORLD,
        /** The plugin was disabled. */
        DISABLED
    }

    /** A swing and the interaction it causes arrive together; one click is one press. */
    static final long CLICK_GAP_MS = 120L;
    /** How long after the last notch the hotbar slot is put back where it was. */
    static final long SLOT_REST_MS = 450L;

    final SpatialInput input;
    final Player viewer;
    final boolean observing;
    private final List<Hotspot> hotspots = new CopyOnWriteArrayList<>();
    private volatile @Nullable Hotspot hovered;
    private volatile boolean enabled = true;
    /** Where it lives once {@link #deliverOn} moved it; {@code null} on the player's thread. */
    private volatile @Nullable Location region;
    private @Nullable Consumer<SpatialClick> onAnyClick;
    private String hoverSound;
    private String pressSound;
    private @Nullable Consumer<SpatialClick> onClick;
    private @Nullable IntConsumer onScroll;
    private @Nullable Runnable onSneak;
    private @Nullable Runnable onLeave;
    private @Nullable Runnable onTick;
    private @Nullable Consumer<End> onEnd;
    private long lastClick;
    private volatile long mutedUntil;
    private volatile long lastNotch;
    private final int originSlot;
    private volatile boolean slotMoved;
    private volatile @Nullable TaskHandle timer;
    private volatile boolean ended;

    SpatialPointer(SpatialInput input, Player viewer, boolean observing) {
        this.input = input;
        this.viewer = viewer;
        this.observing = observing;
        SpatialTheme theme = SpatialTheme.defaults();
        this.hoverSound = theme.hoverSound();
        this.pressSound = theme.selectSound();
        // An observer never moves the hotbar, so it never reads it (it may be made on another thread).
        this.originSlot = observing ? 0 : viewer.getInventory().getHeldItemSlot();
    }

    /** Starts polling where it lives. */
    void start() {
        Location at = region;
        timer = at == null ? input.tasks.runAtEntityTimer(viewer, 1L, 2L, this::poll)
                : input.tasks.runAtLocationTimer(at, 1L, 2L, this::poll);
    }

    /**
     * Moves it onto the thread that owns {@code region} (a match's first seat, a station): polls,
     * pickers, hover visuals ({@link Hotspot#of} buttons included) and every callback run there from
     * now on, so a round living on one region needs no hop of its own. Clicks are still heard on the
     * player's thread and handed over. {@code null} brings it back to the player's thread. Calling it
     * again with the same place does nothing.
     *
     * @since 1.410.0
     */
    public @NotNull SpatialPointer deliverOn(@Nullable Location region) {
        if (ended || Objects.equals(this.region, region)) return this;
        this.region = region == null ? null : region.clone();
        TaskHandle old = timer;
        if (old != null) old.cancel();
        start();
        return this;
    }

    /** Where it lives, or {@code null} on the player's thread. @since 1.410.0 */
    public @Nullable Location region() {
        Location at = region;
        return at == null ? null : at.clone();
    }

    /** Whether it is an {@linkplain SpatialInput#observe observer}. @since 1.410.0 */
    public boolean observing() {
        return observing;
    }

    /** Runs {@code work} where the pointer lives: at once when already there. */
    private void deliver(Runnable work) {
        Location at = region;
        if (at == null || !input.plugin().isEnabled() || input.tasks.isOwnedBy(at)) work.run();
        else input.tasks.runAtLocation(at, work);
    }

    public @NotNull Player viewer() {
        return viewer;
    }

    /** Registers a hotspot and returns it. */
    public @NotNull Hotspot add(@NotNull Hotspot hotspot) {
        hotspots.add(Objects.requireNonNull(hotspot, "hotspot"));
        return hotspot;
    }

    /** Takes a hotspot away; it loses the hover silently. */
    public void remove(@Nullable Hotspot hotspot) {
        if (hotspot == null) return;
        hotspots.remove(hotspot);
        if (hovered == hotspot) {
            hovered = null;
            deliver(() -> hotspot.hover(-1));
        }
    }

    /** Takes every hotspot away: what a view does before drawing the next move's buttons. */
    public void clear() {
        List.copyOf(hotspots).forEach(this::remove);
    }

    public @NotNull List<Hotspot> hotspots() {
        return List.copyOf(hotspots);
    }

    /** The hotspot under the crosshair, or {@code null}. */
    public @Nullable Hotspot hovered() {
        return hovered;
    }

    /** Whether it answers at all: a game waiting on a dealer turns it off and the hover goes. */
    public @NotNull SpatialPointer enabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) unhover();
        return this;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Ignores clicks for {@code millis}: the click that closed something must not press what is under it. */
    public void mute(long millis) {
        mutedUntil = System.currentTimeMillis() + millis;
    }

    /** The sounds played on a hover change and on a press, as {@code NAME|volume|pitch}; blank for none. */
    public @NotNull SpatialPointer sounds(@Nullable String hover, @Nullable String press) {
        this.hoverSound = hover == null ? "" : hover;
        this.pressSound = press == null ? "" : press;
        return this;
    }

    /**
     * Every click, on a hotspot or not, heard before it is routed (a refused press included): an idle
     * clock, a way to stop autoplay. Not heard while disabled or muted.
     *
     * @since 1.410.0
     */
    public @NotNull SpatialPointer onAnyClick(@Nullable Consumer<SpatialClick> onAnyClick) {
        this.onAnyClick = onAnyClick;
        return this;
    }

    /** A click that landed on no hotspot. */
    public @NotNull SpatialPointer onClick(@Nullable Consumer<SpatialClick> onClick) {
        this.onClick = onClick;
        return this;
    }

    /** A notch of the wheel (-1 or +1) over nothing that scrolls. */
    public @NotNull SpatialPointer onScroll(@Nullable IntConsumer onScroll) {
        this.onScroll = onScroll;
        return this;
    }

    /** The player started sneaking: the way out of a screen with no exit button. */
    public @NotNull SpatialPointer onSneak(@Nullable Runnable onSneak) {
        this.onSneak = onSneak;
        return this;
    }

    /** The {@linkplain SpatialInput#hotbar overlay hotbar}'s leave item was right-clicked. */
    public @NotNull SpatialPointer onLeave(@Nullable Runnable onLeave) {
        this.onLeave = onLeave;
        return this;
    }

    /** Runs on every poll (every two ticks), after the hover is updated. */
    public @NotNull SpatialPointer onTick(@Nullable Runnable onTick) {
        this.onTick = onTick;
        return this;
    }

    /**
     * Runs once when it stops, whatever stopped it, on the thread that stopped it. Not run for
     * {@link End#DETACHED}.
     */
    public @NotNull SpatialPointer onEnd(@Nullable Consumer<End> onEnd) {
        this.onEnd = onEnd;
        return this;
    }

    public boolean isAttached() {
        return !ended;
    }

    /** Stops it: the hover goes, the hotbar slot is put back, and the pointer under it takes over. */
    public void detach() {
        end(End.DETACHED);
    }

    void end(End reason) {
        if (ended) return;
        ended = true;
        TaskHandle running = timer;
        if (running != null) running.cancel();
        unhover();
        hotspots.clear();
        if (slotMoved && viewer.isOnline()) restoreSlot();
        input.forget(this);
        if (onEnd != null && reason != End.DETACHED) onEnd.accept(reason);
    }

    /** One poll: what is under the crosshair now. */
    void poll() {
        if (ended) return;
        if (slotMoved && System.currentTimeMillis() - lastNotch > SLOT_REST_MS) {
            slotMoved = false;
            restoreSlot();
        }
        if (observing && (SpatialInput.active(viewer).isPresent() || Tuner.active(viewer).isPresent())) {
            // An attached pointer (a game, a menu) or the tuner has the player: the board is not theirs now.
            setHovered(null, -1);
        } else if (enabled) {
            Location eyes = viewer.getEyeLocation();
            var direction = eyes.getDirection();
            Target target = target(hotspots, eyes.getX(), eyes.getY(), eyes.getZ(),
                    direction.getX(), direction.getY(), direction.getZ());
            if (target == null) setHovered(null, -1);
            else setHovered(target.hotspot(), target.cell());
        }
        if (onTick != null) onTick.run();
    }

    private void setHovered(@Nullable Hotspot next, int cell) {
        Hotspot before = hovered;
        if (before != null && before != next) before.hover(-1);
        hovered = next;
        // Asked on every poll: a hotspot made hoverable again under the crosshair lights now.
        if (next != null && next.hover(cell) && !next.silent()) StageLook.play(viewer, hoverSound);
    }

    /** The hover goes, drawn where the pointer lives. */
    private void unhover() {
        Hotspot before = hovered;
        hovered = null;
        if (before != null) deliver(() -> before.hover(-1));
    }

    private void restoreSlot() {
        if (input.tasks.isOwnedBy(viewer)) viewer.getInventory().setHeldItemSlot(originSlot);
        else input.tasks.runAtEntity(viewer, () -> viewer.getInventory().setHeldItemSlot(originSlot));
    }

    /** A click, heard on the player's thread; answered where the pointer lives. */
    void click(SpatialClick.Kind kind) {
        if (ended || !enabled) return;
        long now = System.currentTimeMillis();
        if (now < mutedUntil || now - lastClick < CLICK_GAP_MS) return;
        lastClick = now;
        boolean sneaking = viewer.isSneaking();
        deliver(() -> route(kind, sneaking));
    }

    private void route(SpatialClick.Kind kind, boolean sneaking) {
        if (ended || !enabled) return;
        // The crosshair moved since the last poll: answer what is under it now, not two ticks ago.
        poll();
        Hotspot target = hovered;
        SpatialClick click = new SpatialClick(kind, sneaking, target == null ? -1 : target.current);
        if (onAnyClick != null) onAnyClick.accept(click);
        if (target != null) {
            if (!target.pressable()) return;
            if (!target.silent() && target.hoverable()) StageLook.play(viewer, pressSound);
            target.press(click);
        } else if (onClick != null) {
            onClick.accept(click);
        }
    }

    /**
     * Whether the crosshair is on one of its hotspots now: what an observer's click on the world is
     * cancelled for. Read on the player's thread; a pointer moved elsewhere answers from its last poll.
     */
    boolean aimed() {
        if (ended || !enabled) return false;
        if (region == null) poll();
        return hovered != null;
    }

    void scroll(int notch) {
        if (ended || !enabled || notch == 0) return;
        if (!observing) {
            lastNotch = System.currentTimeMillis();
            slotMoved = true;
        }
        deliver(() -> {
            Hotspot target = hovered;
            if (target != null && target.scrolls()) target.scroll(notch);
            else if (onScroll != null && !observing) onScroll.accept(notch);
        });
    }

    void sneak() {
        Runnable run = onSneak;
        if (!ended && run != null) deliver(() -> {
            if (!ended) run.run();
        });
    }

    void leave() {
        Runnable run = onLeave;
        if (!ended && run != null) deliver(() -> {
            if (!ended) run.run();
        });
    }

    /** A hotspot and the cell a ray lands on. */
    record Target(Hotspot hotspot, int cell, double distance) {
    }

    /** What a ray from the eyes points at: the highest priority, then the nearest. Pure maths. */
    static @Nullable Target target(Collection<Hotspot> hotspots, double ex, double ey, double ez,
                                   double dx, double dy, double dz) {
        Target best = null;
        for (Hotspot hotspot : hotspots) {
            if (!hotspot.enabled()) continue;
            double[] hit = hotspot.pick(ex, ey, ez, dx, dy, dz);
            if (hit == null) continue;
            if (best == null || hotspot.priority() > best.hotspot().priority()
                    || hotspot.priority() == best.hotspot().priority() && hit[1] < best.distance()) {
                best = new Target(hotspot, (int) hit[0], hit[1]);
            }
        }
        return best;
    }

    /** Which way one notch went, from the slot before to the slot after: -1, +1 or 0. */
    static int notch(int before, int after) {
        int delta = Math.floorMod(after - before, 9);
        if (delta == 0) return 0;
        return delta <= 4 ? 1 : -1;
    }
}
