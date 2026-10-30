package net.exylia.lib.spatial;

import net.exylia.lib.stage.StageLook;
import net.exylia.lib.task.TaskHandle;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
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
 * <p>Clicks on nothing go to {@link #onClick}; the wheel goes to the hovered hotspot when it scrolls,
 * else to {@link #onScroll}. The newest pointer a player has takes their input; when it is detached
 * the one under it (a game they left the menu of) gets it back. Used from the player's thread; every
 * callback runs there.
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
    private final List<Hotspot> hotspots = new ArrayList<>();
    private @Nullable Hotspot hovered;
    private boolean enabled = true;
    private String hoverSound;
    private String pressSound;
    private @Nullable Consumer<SpatialClick> onClick;
    private @Nullable IntConsumer onScroll;
    private @Nullable Runnable onSneak;
    private @Nullable Runnable onLeave;
    private @Nullable Runnable onTick;
    private @Nullable Consumer<End> onEnd;
    private long lastClick;
    private long mutedUntil;
    private long lastNotch;
    private final int originSlot;
    private boolean slotMoved;
    @Nullable TaskHandle timer;
    private boolean ended;

    SpatialPointer(SpatialInput input, Player viewer) {
        this.input = input;
        this.viewer = viewer;
        SpatialTheme theme = SpatialTheme.defaults();
        this.hoverSound = theme.hoverSound();
        this.pressSound = theme.selectSound();
        this.originSlot = viewer.getInventory().getHeldItemSlot();
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
            hotspot.hover(-1);
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
        if (!enabled) setHovered(null, -1);
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

    /** Runs once when it stops, whatever stopped it. Not run for {@link End#DETACHED}. */
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
        if (timer != null) timer.cancel();
        setHovered(null, -1);
        hotspots.clear();
        if (slotMoved && viewer.isOnline()) viewer.getInventory().setHeldItemSlot(originSlot);
        input.forget(this);
        if (onEnd != null && reason != End.DETACHED) onEnd.accept(reason);
    }

    /** One poll: what is under the crosshair now. */
    void poll() {
        if (ended) return;
        if (slotMoved && System.currentTimeMillis() - lastNotch > SLOT_REST_MS) {
            slotMoved = false;
            viewer.getInventory().setHeldItemSlot(originSlot);
        }
        if (enabled) {
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
        if (before == next && (next == null || next.current == cell)) return;
        if (before != null && before != next) before.hover(-1);
        hovered = next;
        if (next != null) {
            next.hover(cell);
            if (!next.silent()) StageLook.play(viewer, hoverSound);
        }
    }

    void click(SpatialClick.Kind kind) {
        if (ended || !enabled) return;
        long now = System.currentTimeMillis();
        if (now < mutedUntil || now - lastClick < CLICK_GAP_MS) return;
        lastClick = now;
        // The crosshair moved since the last poll: answer what is under it now, not two ticks ago.
        poll();
        Hotspot target = hovered;
        SpatialClick click = new SpatialClick(kind, viewer.isSneaking(), target == null ? -1 : target.current);
        if (target != null) {
            if (!target.silent()) StageLook.play(viewer, pressSound);
            target.press(click);
        } else if (onClick != null) {
            onClick.accept(click);
        }
    }

    void scroll(int notch) {
        if (ended || !enabled || notch == 0) return;
        lastNotch = System.currentTimeMillis();
        slotMoved = true;
        Hotspot target = hovered;
        if (target != null && target.scrolls()) target.scroll(notch);
        else if (onScroll != null) onScroll.accept(notch);
    }

    void sneak() {
        if (!ended && onSneak != null) onSneak.run();
    }

    void leave() {
        if (!ended && onLeave != null) onLeave.run();
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
