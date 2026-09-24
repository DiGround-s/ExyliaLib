package net.exylia.lib.display;

import net.exylia.lib.display.internal.DisplayRuntime;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.sequence.Sequence;
import net.exylia.lib.util.sequence.SequenceTarget;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * An effect built in Java and played as one: displays, sounds, particles,
 * sequences and screen shake at millisecond offsets, cancelled as a unit.
 *
 * <pre>{@code
 * Vfx slam = Vfx.at(mob.getLocation()).nearby(48);
 * Telegraphs.circle(slam, 0, centre, 5, 900, 0xFF9500);
 * slam.sound(900, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.6f)
 *     .display(900, rubble, DisplayMotion.chain(rise, fall), centre)
 *     .shake(900, 10);
 * VfxRun run = slam.play(plugin);
 * // The mob died mid wind-up.
 * run.cancel();
 * }</pre>
 *
 * <h2>When to use this and not a sequence</h2>
 * A sequence is written in a file and compiled once, so its geometry is fixed:
 * it cannot know where the target stood, how far away it was, or which way the
 * caster faced. This is for effects whose shape is only known when they play
 * &mdash; a line from a mob to its target, a warning circle on the spot a
 * player was standing &mdash; and it can still play compiled sequences inside
 * itself with {@link #lines}.
 *
 * <h2>How it is played</h2>
 * Everything added is grouped by the tick it falls on, and each group is one
 * {@code runAtLocationLater} on the origin, so a forty-piece effect over two
 * seconds is a handful of scheduler entries and is correct on Folia. What falls
 * on the first tick runs inline when the calling thread already owns the
 * origin. Displays are packets and animate on the client, so a display added at
 * 300 ms is spawned then and moves by itself afterwards.
 *
 * <h2>Who sees it</h2>
 * The viewers are decided once, when the effect is built, and every piece of it
 * goes to that same list: a player who walks up mid-effect does not get the
 * second half of something they never saw start. With nobody watching, playing
 * it schedules nothing.
 *
 * <h2>What it may cost</h2>
 * The whole effect counts against {@code max-per-effect} in
 * {@code displays.yml}: once it holds that many displays, further ones are
 * dropped as they are added and counted in {@link #dropped()}, so what is added
 * first is what survives. Each display still asks the server-wide
 * {@code max-viewer-displays} budget when it is spawned, so a crowded server
 * loses the tail of an effect, never its tick rate.
 *
 * <p>Built on one thread and then played; not meant to be shared while it is
 * still being added to. Played as many times as wanted: each play is its own
 * {@link VfxRun}.
 *
 * @since 1.197.0
 */
public final class Vfx {

    /** Above this many viewers an effect is drawn at a lower level of detail. */
    public static final int LOD_VIEWERS = 15;

    private static final long TICK_MS = 50L;

    private final Location origin;
    private List<Player> viewers = List.of();
    private @Nullable Boolean lod;
    private final List<Step> steps = new ArrayList<>();
    private final List<Shown> shown = new ArrayList<>();
    private int displays;
    private int dropped;
    private long length;

    private Vfx(Location origin) {
        this.origin = origin.clone();
    }

    /**
     * An effect anchored at a place.
     *
     * <p>The origin is where the effect's work is scheduled, which on Folia
     * decides the thread, and where sounds, shake and pieces with no place of
     * their own happen.
     *
     * @param origin where it happens; copied
     * @return an empty effect, seen by nobody until {@link #viewers} or
     *         {@link #nearby} says otherwise
     */
    public static @NotNull Vfx at(@NotNull Location origin) {
        return new Vfx(origin);
    }

    /**
     * Who sees it.
     *
     * @param players the viewers; copied
     * @return this effect
     */
    public @NotNull Vfx viewers(@NotNull Collection<? extends Player> players) {
        this.viewers = List.copyOf(players);
        return this;
    }

    /**
     * Everyone within {@code radius} blocks of the origin sees it, as of now.
     *
     * @param radius how far, in blocks
     * @return this effect
     */
    public @NotNull Vfx nearby(double radius) {
        return viewers(SequenceTarget.at(origin).within(radius).observers());
    }

    /**
     * Forces the level of detail, instead of deciding it from the viewers.
     *
     * @param lower whether to draw fewer pieces
     * @return this effect
     */
    public @NotNull Vfx lod(boolean lower) {
        this.lod = lower;
        return this;
    }

    /**
     * Whether this effect should be drawn with fewer pieces.
     *
     * <p>True when forced, or when more than {@link #LOD_VIEWERS} players are
     * watching: every piece is a packet per viewer, so a crowd is where halving
     * a ring pays. {@link Telegraphs} reads it, and a recipe building its own
     * rings and debris should too.
     */
    public boolean lod() {
        return lod != null ? lod : viewers.size() > LOD_VIEWERS;
    }

    /** Where it happens; a copy. */
    public @NotNull Location origin() {
        return origin.clone();
    }

    /** Who sees it. */
    public @NotNull List<Player> viewers() {
        return viewers;
    }

    // ------------------------------------------------------------------ pieces

    /**
     * A display at the origin.
     *
     * @param atMillis when, from the start of the effect
     * @param model    what it draws
     * @param motion   how it moves, relative to the origin
     * @return this effect
     */
    public @NotNull Vfx display(long atMillis, @NotNull DisplayModel model,
                                @NotNull DisplayMotion motion) {
        return display(atMillis, model, motion, origin);
    }

    /**
     * A display somewhere else.
     *
     * @param atMillis when, from the start of the effect
     * @param model    what it draws
     * @param motion   how it moves, relative to {@code where}
     * @param where    where it stands; copied
     * @return this effect
     */
    public @NotNull Vfx display(long atMillis, @NotNull DisplayModel model,
                                @NotNull DisplayMotion motion, @NotNull Location where) {
        return shown(new Shown(Math.max(0L, atMillis), model, motion, where.clone(), null));
    }

    /**
     * A display seated on an entity, so the client carries it along.
     *
     * <p>Where the entity stands is read now, on the thread building the
     * effect, which is the thread that owns it; if the entity is gone by the
     * time the piece is due, the piece is skipped rather than left floating
     * where it was.
     *
     * @param atMillis when, from the start of the effect
     * @param model    what it draws
     * @param motion   how it moves, relative to the entity
     * @param mount    what it rides
     * @return this effect
     */
    public @NotNull Vfx ride(long atMillis, @NotNull DisplayModel model,
                             @NotNull DisplayMotion motion, @NotNull Entity mount) {
        return shown(new Shown(Math.max(0L, atMillis), model, motion, mount.getLocation(), mount));
    }

    /**
     * A straight segment from one point to another, gone after {@code lifeMillis}.
     *
     * <p>A block model is one display stretched along the segment, {@code
     * thickness} wide and tall: a laser, a beam of light, a lightning bolt when
     * three are joined at jagged points. An item or text model is repeated
     * along it every {@code thickness} blocks instead, at that size &mdash; a
     * chain of links, a line of arrows &mdash; because an item stretched to ten
     * times its length is a smear.
     *
     * @param atMillis   when, from the start of the effect
     * @param from       one end
     * @param to         the other end
     * @param model      what it is made of
     * @param thickness  how thick, in blocks, or the spacing for items
     * @param lifeMillis how long it stays
     * @return this effect
     */
    public @NotNull Vfx beam(long atMillis, @NotNull Location from, @NotNull Location to,
                             @NotNull DisplayModel model, double thickness, long lifeMillis) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double size = Math.max(0.01, thickness);
        if (length < 1.0E-3) {
            return this;
        }
        Rotation along = along(dx, dy, dz);
        if (model.kind() == DisplayModel.Kind.BLOCK) {
            DisplayMotion motion = DisplayMotion.builder().life(lifeMillis)
                    .from(dx / 2, dy / 2, dz / 2).to(dx / 2, dy / 2, dz / 2)
                    .rotation(along)
                    .scale(new double[]{size, size, length}, new double[]{size, size, length})
                    .build();
            return display(atMillis, model, motion, from);
        }
        int pieces = Math.max(1, (int) Math.round(length / size));
        for (int piece = 0; piece < pieces; piece++) {
            double at = (piece + 0.5) / pieces;
            DisplayMotion motion = DisplayMotion.builder().life(lifeMillis)
                    .from(dx * at, dy * at, dz * at).to(dx * at, dy * at, dz * at)
                    .rotation(along).scale(size, size).build();
            display(atMillis, model, motion, from);
        }
        return this;
    }

    /**
     * The rotation that lays a model's own +Z along a direction.
     *
     * <p>Pitched first about its own X and then turned about Y: the order a
     * spear is aimed in, and the one that keeps it from rolling.
     */
    static @NotNull Rotation along(double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-9) {
            return Rotation.NONE;
        }
        double pitch = -Math.asin(Math.clamp(dy / length, -1.0, 1.0));
        double yaw = Math.atan2(dx, dz);
        return Rotation.around(Rotation.Axis.X, pitch).then(Rotation.around(Rotation.Axis.Y, yaw));
    }

    /**
     * A sound at the origin.
     *
     * @param atMillis when, from the start of the effect
     * @param sound    what
     * @param volume   how loud; also how far it carries
     * @param pitch    0.5 to 2
     * @return this effect
     */
    public @NotNull Vfx sound(long atMillis, @NotNull Sound sound, float volume, float pitch) {
        return sound(atMillis, origin, sound, volume, pitch);
    }

    /**
     * A sound somewhere else.
     *
     * @param atMillis when, from the start of the effect
     * @param where    where it comes from; copied
     * @param sound    what
     * @param volume   how loud; also how far it carries
     * @param pitch    0.5 to 2
     * @return this effect
     */
    public @NotNull Vfx sound(long atMillis, @NotNull Location where, @NotNull Sound sound,
                              float volume, float pitch) {
        Location at = where.clone();
        return step(atMillis, 0L, run -> {
            for (Player viewer : run.sameWorld(at)) {
                viewer.playSound(at, sound, volume, pitch);
            }
        });
    }

    /**
     * Particles, the plain way.
     *
     * @param atMillis when, from the start of the effect
     * @param particle what
     * @param where    where; copied
     * @param count    how many
     * @return this effect
     */
    public @NotNull Vfx particle(long atMillis, @NotNull Particle particle, @NotNull Location where,
                                 int count) {
        return particle(atMillis, particle, where, count, 0, 0, 0, 0, null);
    }

    /**
     * Particles, with every knob the client takes.
     *
     * @param atMillis when, from the start of the effect
     * @param particle what
     * @param where    where; copied
     * @param count    how many
     * @param dx       spread east, or a direction when {@code count} is 0
     * @param dy       spread up
     * @param dz       spread south
     * @param speed    how fast they leave
     * @param data     what the particle needs, such as dust options, or {@code null}
     * @return this effect
     */
    public @NotNull Vfx particle(long atMillis, @NotNull Particle particle, @NotNull Location where,
                                 int count, double dx, double dy, double dz, double speed,
                                 @Nullable Object data) {
        Location at = where.clone();
        return step(atMillis, 0L, run -> {
            for (Player viewer : run.sameWorld(at)) {
                viewer.spawnParticle(particle, at, count, dx, dy, dz, speed, data);
            }
        });
    }

    /**
     * A compiled sequence, started at an offset.
     *
     * <p>It keeps its own audience &mdash; the target's radius and visibility
     * &mdash; because that is what the sequence was written against. Cancelling
     * the effect cancels it with the rest.
     *
     * @param atMillis when, from the start of the effect
     * @param sequence what to play
     * @param target   where, and for whom
     * @return this effect
     */
    public @NotNull Vfx lines(long atMillis, @NotNull Sequence sequence,
                              @NotNull SequenceTarget target) {
        return step(atMillis, sequence.durationMillis(), run -> run.owns(
                net.exylia.lib.util.sequence.Sequences.of(run.plugin()).play(sequence, target)));
    }

    /**
     * Tilts the camera of the viewers within {@code radius} blocks, once.
     *
     * @param atMillis when, from the start of the effect
     * @param radius   how far from the origin, in blocks
     * @return this effect
     */
    public @NotNull Vfx shake(long atMillis, double radius) {
        return shake(atMillis, radius, 1, 0L);
    }

    /**
     * Tilts the camera of the viewers within {@code radius} blocks, several
     * times.
     *
     * <p>The hurt animation a hit plays, with no hit: no damage, no sound. How
     * far it tilts is the viewer's own damage-tilt setting, so the strength of
     * a shake is how many times it comes and how close together; two a tick
     * apart already reads as a heavy blow.
     *
     * @param atMillis    when the first one comes, from the start of the effect
     * @param radius      how far from the origin, in blocks
     * @param times       how many
     * @param everyMillis how long between them
     * @return this effect
     */
    public @NotNull Vfx shake(long atMillis, double radius, int times, long everyMillis) {
        for (int beat = 0; beat < Math.max(1, times); beat++) {
            step(atMillis + beat * Math.max(TICK_MS, everyMillis), 0L, run ->
                    net.exylia.lib.packet.internal.ScreenShake.shake(origin, run.viewers(), radius));
        }
        return this;
    }

    /**
     * Anything else, at an offset, on the origin's thread.
     *
     * <p>For visuals that need the moment they happen in: a block broken into
     * particles where the ground is by then, an afterimage where a charging
     * mob has got to. Not for gameplay: an effect with nobody watching does not
     * run at all.
     *
     * @param atMillis when, from the start of the effect
     * @param action   what to do
     * @return this effect
     */
    public @NotNull Vfx call(long atMillis, @NotNull Runnable action) {
        return step(atMillis, 0L, run -> action.run());
    }

    // ------------------------------------------------------------ inspection

    /** How many displays this effect will spawn per viewer. */
    public int displays() {
        return displays;
    }

    /** How many displays were refused for going over {@code max-per-effect}. */
    public int dropped() {
        return dropped;
    }

    /** How long, from the start, until its last piece is gone. */
    public long lengthMillis() {
        return length;
    }

    /** Whether nothing has been added. */
    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /** The displays added, in order, for tests that check geometry. */
    @NotNull List<Shown> shown() {
        return shown;
    }

    // ------------------------------------------------------------------ play

    /**
     * Plays it.
     *
     * @param plugin whose effect this is: disabling it cancels what is still
     *               to come and takes its displays off every screen
     * @return the run, for cancelling
     */
    public @NotNull VfxRun play(@NotNull Plugin plugin) {
        boolean idle = viewers.isEmpty() || steps.isEmpty();
        VfxRun run = new VfxRun(plugin, viewers,
                System.currentTimeMillis() + (idle ? 0L : length));
        if (idle) {
            return run;
        }
        Map<Long, List<Step>> byTick = new TreeMap<>();
        for (Step step : steps) {
            // Rounded, not floored: a piece at 70 ms lands on the tick at 50
            // rather than at 100, so nothing is more than half a tick late.
            byTick.computeIfAbsent((step.atMillis() + TICK_MS / 2) / TICK_MS,
                    tick -> new ArrayList<>()).add(step);
        }
        TaskScheduler tasks = Tasks.of(plugin);
        for (Map.Entry<Long, List<Step>> group : byTick.entrySet()) {
            long tick = group.getKey();
            List<Step> due = group.getValue();
            Runnable fire = () -> run.fire(due);
            if (tick == 0L && tasks.isOwnedBy(origin)) {
                fire.run();
            } else if (tick == 0L) {
                run.owns(tasks.runAtLocation(origin, fire));
            } else {
                run.owns(tasks.runAtLocationLater(origin, tick, fire));
            }
        }
        return run;
    }

    // --------------------------------------------------------------- inside

    private Vfx shown(Shown piece) {
        int ceiling = DisplayRuntime.maxPerEffect();
        if (displays >= ceiling) {
            dropped++;
            return this;
        }
        displays++;
        shown.add(piece);
        return step(piece.atMillis(), piece.motion().lifeMillis(), run -> run.show(piece));
    }

    private Vfx step(long atMillis, long lastsMillis, Consumer<VfxRun> action) {
        long at = Math.max(0L, atMillis);
        steps.add(new Step(at, action));
        length = Math.max(length, at + Math.max(0L, lastsMillis));
        return this;
    }

    /** One piece of the timeline. */
    record Step(long atMillis, Consumer<VfxRun> action) {
    }

    /**
     * One display the effect will spawn.
     *
     * @param atMillis when
     * @param model    what it draws
     * @param motion   how it moves
     * @param where    where it stands before the motion's offsets
     * @param mount    what it rides, or {@code null}
     */
    record Shown(long atMillis, DisplayModel model, DisplayMotion motion, Location where,
                 @Nullable Entity mount) {
    }
}
