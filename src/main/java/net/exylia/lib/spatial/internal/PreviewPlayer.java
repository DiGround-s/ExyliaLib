package net.exylia.lib.spatial.internal;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcMotion;
import net.exylia.lib.npc.Npcs;
import net.exylia.lib.ragdoll.RagdollModel;
import net.exylia.lib.spatial.SpatialLayout;
import net.exylia.lib.spatial.SpatialPreview;
import net.exylia.lib.stage.StageLook;
import net.exylia.lib.stage.StagePlane;
import net.exylia.lib.util.sequence.SequenceRun;
import net.exylia.lib.util.sequence.SequenceTarget;
import net.exylia.lib.util.sequence.Sequences;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plays a {@link SpatialPreview} on a plane of the world for one viewer: a body in what they are trying on, an item,
 * a performance, an effect on a mannequin, a projectile in flight. A spatial menu's pedestal and a screen's window
 * both play through this; the host says where and draws the pieces.
 *
 * <p>Internal: free to change without notice.
 */
public abstract class PreviewPlayer {

    /** How long a fresh preview body waits for its skin's picture before it plays anyway. */
    private static final long SKIN_WAIT_MILLIS = 400L;
    /** How long a finished performance rests before it starts again. */
    private static final long ENCORE_MILLIS = 600L;
    /** The slots a preview body is dressed in, in the order they are sent. */
    private static final List<EquipmentSlot> DRESSED = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND);
    /** The longest a rehearsal is watched before the view is chosen from what it showed. */
    private static final long WATCH_MILLIS = 6000L;
    /** How far over the board a rehearsal plays, and how small: out of sight and out of earshot. */
    protected static final double REHEARSAL_UP = 48;
    private static final double REHEARSAL_SCALE = 0.05;
    /**
     * What each effect's first play taught (by sequence, facing, zone and scale): the turn and the fit, so every
     * later play, in any menu, shows it best at once. Memory only.
     */
    private static final Map<String, EffectView.Tuning> TUNED = new java.util.concurrent.ConcurrentHashMap<>();
    /** The longest a piece of the preview stays drawn. */
    protected static final long LIFE = 30 * 60_000L;

    protected final Plugin plugin;
    protected final Player viewer;
    protected final StagePlane plane;
    protected final Rotation rotation;
    protected final Location base;
    protected @Nullable NpcHandle npc;
    protected @Nullable DisplayHandle item;
    private SpatialPreview current = SpatialPreview.none();
    private @Nullable SequenceRun run;
    private long nextPlay;
    private long npcUntil;
    /** Whether a mannequin is taken down while its effect plays: it stands again once the effect is gone. */
    private boolean hidden;
    /** What the NPC was last sent, slot by slot, so a re-dress sends only what changed. */
    private final Map<EquipmentSlot, ItemStack> dressed = new EnumMap<>(EquipmentSlot.class);
    /** A performance's runs: one body, or a duet's two. */
    private final List<SequenceRun> acts = new ArrayList<>(2);
    /** Whose skin a mannequin with nobody named wears, picked each time it stands, worn by its effect too. */
    private @Nullable net.exylia.lib.npc.PreviewSkins.Body body;
    /** A duet's second body, from {@code preview.skins}. */
    private @Nullable net.exylia.lib.npc.PreviewSkins.Body partner;
    /** A performance's body in the skin it was handed, built once rather than on every encore. */
    private @Nullable RagdollModel own;
    /** When that body stood; its first play waits up to {@link #SKIN_WAIT_MILLIS} for its skin to be read. */
    private long stoodAt;
    /** A flight's target and the projectile in the air (or stuck in it), with what they set off. */
    private @Nullable DisplayHandle target;
    private @Nullable DisplayHandle projectile;
    private final List<SequenceRun> flightRuns = new ArrayList<>();
    /** When the projectile last set off, 0 before the first; the trail steps drawn since; whether it landed. */
    private long launchedAt;
    private int trailSteps;
    private boolean landed;
    /** An effect played out of sight to learn how to show it, what it showed, where and since when. */
    private @Nullable SequenceRun rehearsal;
    private @Nullable List<double[]> rehearsing;
    private @Nullable Location rehearsalBase;
    private long rehearsalSince;

    protected PreviewPlayer(Plugin plugin, Player viewer, StagePlane plane, Rotation rotation) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.plane = plane;
        this.rotation = rotation;
        this.base = plane.point(0, 0, 0);
    }

    /** How the preview is sized, read every time: a layout reload shows at once. */
    protected abstract SpatialLayout.Preview look();

    /** Draws one piece of the preview for the viewer, or returns {@code null} if it could not be. */
    protected abstract @Nullable DisplayHandle show(DisplayModel model, DisplayMotion motion, Location at);

    /** Takes one piece down. */
    protected abstract void drop(@Nullable DisplayHandle handle);

    /** Every piece of the host's own, left out when a rehearsal measures what an effect drew. */
    protected abstract java.util.Collection<?> pieces();

    /** A piece standing still at this size. */
    protected DisplayMotion still(Rotation rotation, double x, double y, double z) {
        return StageLook.still(rotation, x, y, z, LIFE);
    }

    /** How a body keeps turning, its yaw set in {@code stand}. */
    protected NpcMotion standing(Location stand, SpatialLayout.Preview look) {
        stand.setYaw((float) (stand.getYaw() + look.facingDegrees()));
        return look.turnSpeed() == 0 ? NpcMotion.still() : NpcMotion.builder().spin(look.turnSpeed()).build();
    }

    /** How fast an item on show turns. */
    protected double itemSpeed(SpatialLayout.Preview look) {
        return look.turnSpeed();
    }

    /** How to read a {@code plane}'s direction from a plane's own terms. */
    public static double[] offset(StagePlane plane, double across, double up, double forward) {
        Location from = plane.point(0, 0, 0);
        Location to = plane.point(across, up, forward);
        return new double[]{to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ()};
    }

    /**
     * Turns an item to point along a direction: an item is drawn tip up and to the east, so it is laid
     * flat along east, raised to the slope, then swung round to the heading.
     */
    private static Rotation pointing(Vector velocity) {
        double horizontal = Math.hypot(velocity.getX(), velocity.getZ());
        return Rotation.around(Rotation.Axis.Z, -Math.PI / 4)
                .then(Rotation.around(Rotation.Axis.Z, Math.atan2(velocity.getY(), horizontal)))
                .then(Rotation.around(Rotation.Axis.Y, Math.atan2(-velocity.getZ(), velocity.getX())));
    }

    // A click (on the input's thread) and the poll (on the viewer's) both drive the stage: one at a time, so a
    // play the poll starts as a selection lands can never outlive it.
    public synchronized void update(SpatialPreview next) {
        if (next == null) next = SpatialPreview.none();
        if (current instanceof SpatialPreview.LiveNpc && next instanceof SpatialPreview.LiveNpc live
                && next.equals(current) && npc != null && npc.isShowing()) {
            // The same body: only its supplier is new, read at once.
            current = next;
            dress(live.equipment().get());
            return;
        }
        if (next.equals(current) && (npc == null || npc.isShowing())) return;
        if (current instanceof SpatialPreview.Npc was && next instanceof SpatialPreview.Npc now
                && was.skin().equals(now.skin()) && was.equipment() != null && now.equipment() != null
                && npc != null && npc.isShowing()) {
            current = next;
            dress(now.equipment());
            return;
        }
        remove();
        current = next;
        draw();
    }

    protected void draw() {
        SpatialLayout.Preview look = look();
        Location stand = base.clone();
        stand.setYaw(plane.yaw() + 180);
        switch (current) {
            case SpatialPreview.Npc body -> {
                // Nameless: under the viewer's own name the client would hang their scoreboard team's
                // prefix (a rank) over it.
                NpcModel model = NpcModel.of(body.skin()).scale(look.scale()).nameless();
                if (body.equipment() == null) model = model.wearing(body.skin());
                spawn(model, stand, look);
                dress(body.equipment());
            }
            case SpatialPreview.LiveNpc body -> {
                spawn(NpcModel.of(body.skin()).scale(look.scale()).nameless(), stand, look);
                dress(body.equipment().get());
            }
            case SpatialPreview.Performance act -> {
                body = act.skin() == null ? look.skinPolicy().pick(viewer) : null;
                partner = act.isDuet() ? look.skinPolicy().pick(viewer) : null;
                own = act.skin() == null ? null : RagdollModel.of(act.skin());
                stoodAt = System.currentTimeMillis();
                nextPlay = 0;
            }
            case SpatialPreview.Item shown -> {
                // The item frame's context: a shield or a banner shows its front, centred. Still at turn-speed 0,
                // a sway of a few degrees; turned by the viewer with turn-controls (since 1.470.1).
                double size = 0.62 * look.scale();
                double speed = itemSpeed(look);
                item = show(DisplayModel.item(shown.item()).held(ItemFacing.FIXED).light(15),
                        ItemFacing.stand(rotation, look.facingDegrees(), speed, size, LIFE),
                        base.clone().add(0, 0.33 * look.scale(), 0));
            }
            case SpatialPreview.Effect effect -> {
                mannequin(effect);
                nextPlay = 0;
            }
            case SpatialPreview.Flight flight -> {
                double size = Flights.TARGET * look.scale();
                target = show(DisplayModel.block(Material.TARGET.createBlockData()).light(15),
                        still(rotation, size, size, size), plane.point(0, size / 2, 0));
                nextPlay = 0;
            }
            case SpatialPreview.None none -> { }
        }
    }

    /** A flight, every poll: sets off again when due, draws its trail where it is, its impact once it lands. */
    private void fly(SpatialPreview.Flight flight, long now) {
        if (now >= nextPlay) launch(flight, now);
        long flown = now - launchedAt;
        if (flight.trail() != null && flown < Flights.FLIGHT_MILLIS) {
            // At most one step a poll, where the projectile is now: a step missed between two polls is
            // not drawn late, behind it.
            int step = (int) (flown / (flight.trailEveryTicks() * 50L));
            if (step > trailSteps) {
                trailSteps = step;
                play(flight.trail(), (double) flown / Flights.FLIGHT_MILLIS);
            }
        }
        if (!landed && flown >= Flights.FLIGHT_MILLIS) {
            landed = true;
            if (flight.impact() != null) play(flight.impact(), 1);
        }
    }

    private void launch(SpatialPreview.Flight flight, long now) {
        drop(projectile);
        flightRuns.removeIf(SequenceRun::isFinished);
        launchedAt = now;
        trailSteps = 0;
        landed = false;
        long impact = flight.impact() == null || flight.impact().isEndless() ? 0
                : Math.min(3000L, flight.impact().durationMillis());
        long cycle = Math.max(Flights.CYCLE_MILLIS, Flights.FLIGHT_MILLIS + impact + 700L);
        nextPlay = now + cycle;
        double scale = look().scale();
        double size = 0.5 * scale;
        double[] start = Flights.at(0, scale);
        List<DisplayKeyframe> poses = new ArrayList<>();
        for (long at = 0; at <= Flights.FLIGHT_MILLIS; at += 100) {
            double progress = (double) at / Flights.FLIGHT_MILLIS;
            double[] here = Flights.at(progress, scale);
            double[] moved = offset(plane, here[0] - start[0], here[1] - start[1], 0);
            poses.add(new DisplayKeyframe(at, (float) moved[0], (float) moved[1], (float) moved[2],
                    pointing(headingAt(progress)), (float) size, (float) size, (float) size));
        }
        // Stuck in the target until it sets off again.
        poses.add(poses.get(poses.size() - 1).at(cycle));
        projectile = show(DisplayModel.item(flight.projectile()).light(15), DisplayMotion.of(poses, cycle + 200L),
                plane.point(start[0], start[1], 0));
        if (flight.launch() != null) play(flight.launch(), 0);
    }

    /** Plays a moment of the flight where the projectile is at {@code progress}, facing the way it flies. */
    private void play(net.exylia.lib.util.sequence.Sequence sequence, double progress) {
        double[] here = Flights.at(progress, look().scale());
        Location at = plane.point(here[0], here[1], 0);
        // An impact faces along the stage, level, as a real hit faces the way the shot came.
        at.setDirection(progress >= 1 ? headingAt(-1) : headingAt(progress));
        flightRuns.add(Sequences.of(plugin).play(sequence, SequenceTarget.at(at).by(viewer).onlyTo(viewer)
                .scale(look().effectScale())));
    }

    /** The projectile's heading in the world; a negative progress is level, to the viewer's right. */
    private Vector headingAt(double progress) {
        double[] heading = progress < 0 ? new double[]{1, 0} : Flights.heading(progress, 1);
        double[] world = offset(plane, heading[0], heading[1], 0);
        return new Vector(world[0], world[1], world[2]);
    }

    private void mannequin(SpatialPreview.Effect effect) {
        Location stand = base.clone();
        // Turned as the effect will play, so the body that dies is the one that stood there.
        stand.setYaw((float) (plane.yaw() + 180 + effectTurn(effect)));
        body = effect.skin() == null ? look().skinPolicy().pick(viewer) : null;
        stoodAt = System.currentTimeMillis();
        NpcModel model = body != null ? body.npc() : NpcModel.of(effect.skin());
        // The size the effect plays at, so the body that dies is the one that stood there.
        spawn(model.scale(effectScale(effect)).nameless(), stand, look());
    }

    /** How an effect is turned from facing the viewer: its own facing, the layout's, or what was learned. */
    private double effectTurn(SpatialPreview.Effect effect) {
        String facing = effect.facing() != null ? effect.facing() : look().effectFacing();
        double fixed = SpatialLayout.Preview.degreesOf(facing);
        if (!Double.isNaN(fixed)) return fixed;
        EffectView.Tuning learned = TUNED.get(key(effect));
        return learned == null ? 0 : learned.turn();
    }

    /** How large an effect plays: the layout's effect-scale, smaller when its zone needs it. */
    private double effectScale(SpatialPreview.Effect effect) {
        EffectView.Tuning learned = TUNED.get(key(effect));
        return look().effectScale() * (learned == null ? 1 : learned.scale());
    }

    private String key(SpatialPreview.Effect effect) {
        List<Double> zone = look().zone();
        String facing = effect.facing() != null ? effect.facing() : look().effectFacing();
        return System.identityHashCode(effect.sequence()) + "|" + facing + "|" + zone + "|" + look().effectScale();
    }

    /** Whether this effect's view is still to be learned from the play about to start. */
    private boolean learning(SpatialPreview.Effect effect) {
        if (TUNED.containsKey(key(effect))) return false;
        String facing = effect.facing() != null ? effect.facing() : look().effectFacing();
        List<Double> zone = look().zone();
        return Double.isNaN(SpatialLayout.Preview.degreesOf(facing)) || zone.get(0) > 0 && zone.get(1) > 0;
    }

    /**
     * Learns how to show an effect before anybody sees it: plays it once out of sight (a miniature far over
     * the board, too small and too far to see or hear), watches where its pieces go, and chooses its turn
     * and its size from that. Every visible play, the first included, then looks the same.
     */
    private void rehearse(SpatialPreview.Effect effect, long now) {
        if (rehearsal != null) return;
        Location far = base.clone().add(0, REHEARSAL_UP, 0);
        far.setYaw(plane.yaw() + 180);
        rehearsing = new ArrayList<>();
        rehearsalBase = far;
        rehearsalSince = now;
        SequenceTarget where = SequenceTarget.at(far).by(viewer).onlyTo(viewer).scale(REHEARSAL_SCALE);
        where = body != null ? where.wearing(body.ragdoll()) : where.wearing(net.exylia.lib.npc.PreviewSkins.extra(0));
        rehearsal = Sequences.of(plugin).play(effect.sequence(), where);
    }

    /** While a rehearsal runs: where its pieces are; once it is over (or has run long enough), the choice. */
    private void watch() {
        if (rehearsal == null) return;
        java.util.Set<Object> ours = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        ours.addAll(pieces());
        rehearsing.addAll(net.exylia.lib.display.internal.DisplayRuntime.sample(viewer, rehearsalBase, 3, ours));
        if (!rehearsal.isFinished() && System.currentTimeMillis() - rehearsalSince < WATCH_MILLIS) return;
        rehearsal.cancel();
        if (current instanceof SpatialPreview.Effect effect) {
            // Back to the size it plays at, about a base at the origin.
            double grow = look().effectScale() / REHEARSAL_SCALE;
            List<double[]> samples = new ArrayList<>(rehearsing.size());
            for (double[] sample : rehearsing) {
                samples.add(new double[]{(sample[0] - rehearsalBase.getX()) * grow, (sample[1] - rehearsalBase.getY()) * grow,
                        (sample[2] - rehearsalBase.getZ()) * grow, sample[3] * grow, sample.length > 4 ? sample[4] : 0});
            }
            String facing = effect.facing() != null ? effect.facing() : look().effectFacing();
            double fixed = SpatialLayout.Preview.degreesOf(facing);
            List<Double> zone = look().zone();
            TUNED.put(key(effect), EffectView.choose(samples, new double[]{0, 0, 0},
                    plane.yaw(), 0, Double.isNaN(fixed), Double.isNaN(fixed) ? 0 : fixed,
                    new double[]{zone.get(0), zone.get(1)}));
            // The mannequin stands as the effect will play, once the play it stood for is over.
            if (npc != null && (run == null || run.isFinished())) {
                npc.remove();
                npc = null;
                mannequin(effect);
            }
        }
        rehearsal = null;
        rehearsing = null;
    }

    private void spawn(NpcModel model, Location stand, SpatialLayout.Preview look) {
        NpcMotion turn = standing(stand, look);
        npc = Npcs.of(plugin).show(model, turn, stand, 120_000L, List.of(viewer));
        npcUntil = System.currentTimeMillis() + 115_000L;
    }

    /** Sends the slots whose item differs from what the NPC was last sent; a fresh NPC wears nothing. */
    private void dress(@Nullable Map<EquipmentSlot, ItemStack> equipment) {
        if (npc == null || equipment == null) return;
        Map<EquipmentSlot, ItemStack> changed = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : DRESSED) {
            ItemStack item = equipment.get(slot);
            if (item != null && item.getType().isAir()) item = null;
            if (Objects.equals(item, dressed.get(slot))) continue;
            changed.put(slot, item);
            if (item == null) dressed.remove(slot);
            else dressed.put(slot, item.clone());
        }
        // One packet for every slot that moved, not one a slot.
        if (!changed.isEmpty()) npc.equip(changed);
    }

    /**
     * A performance, every poll: plays once its bodies' skins are read (or after a short wait), and a
     * finite one again once every piece of the last play is gone. There is no mannequin to hide.
     */
    private void perform(SpatialPreview.Performance act, long now) {
        if (!acts.isEmpty()) {
            for (SequenceRun playing : acts) {
                if (!playing.isFinished()) return;
            }
            acts.clear();
            nextPlay = now + ENCORE_MILLIS;
        }
        if (now < nextPlay) return;
        boolean ready = (body == null || body.ready()) && (partner == null || partner.ready());
        if (!ready && now - stoodAt <= SKIN_WAIT_MILLIS) return;
        Location at = base.clone();
        if (!act.isDuet()) {
            acts.add(Sequences.of(plugin).play(act.sequence(), performer(at, body, act.skin())));
            return;
        }
        // As a duet plays between two players: both on one line, the emote's gap apart (at the size it
        // plays), turned towards each other, so the choreography meets in the middle.
        double half = act.gap() * look().effectScale() / 2;
        Location first = plane.point(-half, 0, 0);
        Location second = plane.point(half, 0, 0);
        first.setDirection(second.toVector().subtract(first.toVector()));
        second.setDirection(first.toVector().subtract(second.toVector()));
        acts.add(Sequences.of(plugin).play(act.sequence(), performer(first, body, act.skin())));
        acts.add(Sequences.of(plugin).play(act.sequence(), performer(second, partner, null)));
    }

    /** Where one body performs and whose skin it wears: {@code skin}'s, or the picked body's. */
    private SequenceTarget performer(Location at, @Nullable net.exylia.lib.npc.PreviewSkins.Body worn,
                                     @Nullable Player skin) {
        SequenceTarget where = SequenceTarget.at(at).by(viewer).onlyTo(viewer)
                .scale(look().effectScale());
        if (worn != null) return where.wearing(worn.ragdoll()).standing(worn::npc);
        return skin != null && own != null ? where.wearing(own) : where;
    }

    /** Every poll: an NPC lives two minutes at most, so a long look gets a fresh one; an effect plays again. */
    public synchronized void keep() {
        watch();
        long now = System.currentTimeMillis();
        if (current instanceof SpatialPreview.Flight flight) {
            fly(flight, now);
            return;
        }
        if (current instanceof SpatialPreview.Performance act) {
            perform(act, now);
            return;
        }
        // Gone while its effect plays, and back only once every piece of it is: never two bodies.
        if (current instanceof SpatialPreview.Effect effect && npc == null && hidden
                && (run == null || run.isFinished())) {
            hidden = false;
            mannequin(effect);
            nextPlay = now + 1000L;
        }
        if (npc != null && (now > npcUntil || !npc.isShowing())) {
            SpatialPreview again = current;
            remove();
            current = again;
            draw();
            return;
        }
        if (current instanceof SpatialPreview.LiveNpc live && npc != null) dress(live.equipment().get());
        if (current instanceof SpatialPreview.Effect effect && npc != null && now >= nextPlay
                && (body == null || body.ready() || now - stoodAt > SKIN_WAIT_MILLIS)) {
            if (run != null) run.cancel();
            // Where the mannequin's feet were, the pedestal's top: a kill effect is written round the
            // victim's feet. It plays as a miniature of itself (effect-scale) so all of it stays
            // between the carousel and the header.
            // Learned out of sight while the first play shows at the layout's size: every selection plays at
            // once, and the plays after the first are turned and sized from what the rehearsal saw.
            if (learning(effect)) rehearse(effect, now);
            Location at = base.clone();
            // Facing the viewer (or turned to show its motion best, never its back): the effect is written
            // round the victim, who faces where it is played.
            double turn = effectTurn(effect);
            at.setYaw((float) (plane.yaw() + 180 + turn));
            // The viewer sets it off; [RAGDOLL] and [NPC] {victim} lines wear the mannequin's skin, so
            // the body that falls is the one that stood there. No target, as in the previews' mannequin
            // mode: a [POTION] or a victim's title never lands on anybody.
            SequenceTarget where = SequenceTarget.at(at).by(viewer).onlyTo(viewer)
                    .scale(effectScale(effect));
            where = body != null ? where.wearing(body.ragdoll()).standing(body::npc)
                    : where.wearing(RagdollModel.of(effect.skin()));
            run = Sequences.of(plugin).play(effect.sequence(), where);
            long length = effect.sequence().isEndless() ? Long.MAX_VALUE / 4 : effect.sequence().durationMillis();
            nextPlay = now + Math.max(2500L, length + 1500L);
            if (look().vanishOnPlay()) {
                // As the previews' vanish-on-hit: the mannequin is gone while it plays, so a kill reads
                // as a death. It stands again once the effect's last piece is gone (never while an
                // endless one plays), and the next play waits a second after that.
                npc.remove();
                npc = null;
                hidden = true;
                nextPlay = Long.MAX_VALUE;
            }
        }
    }

    /** Plays the preview again from the start: an effect at once, its mannequin back if it was gone. */
    public synchronized void replay() {
        SpatialPreview again = current;
        remove();
        current = again;
        draw();
        keep();
    }

    public synchronized void remove() {
        if (rehearsal != null) rehearsal.cancel();
        rehearsal = null;
        rehearsing = null;
        if (npc != null) npc.remove();
        npc = null;
        drop(item);
        item = null;
        if (run != null) run.cancel();
        run = null;
        drop(target);
        drop(projectile);
        target = null;
        projectile = null;
        flightRuns.forEach(SequenceRun::cancel);
        flightRuns.clear();
        acts.forEach(SequenceRun::cancel);
        acts.clear();
        dressed.clear();
        body = null;
        partner = null;
        own = null;
        launchedAt = 0;
        hidden = false;
        current = SpatialPreview.none();
    }
}
