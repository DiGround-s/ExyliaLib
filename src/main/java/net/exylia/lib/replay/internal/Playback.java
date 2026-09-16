package net.exylia.lib.replay.internal;

import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcPose;
import net.exylia.lib.npc.internal.NpcRuntime;
import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayMark;
import net.exylia.lib.replay.ReplayPlayback;
import net.exylia.lib.task.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One recording while somebody is watching it.
 *
 * <h2>It is the client that makes it look real</h2>
 * A body is put where it was with a relative step, and the client draws its own
 * frames between one step and the next exactly as it does for a real player.
 * Nothing about a playback is smoothed or animated here; what makes it
 * indistinguishable from spectating is that it is the same packets a real
 * player's movement produces.
 *
 * <h2>Only what changed</h2>
 * A frame is applied when the tick it belongs to changes, not every time the
 * driver runs: at a quarter speed that is one set of packets every four ticks
 * rather than four copies of the same one.
 */
@ApiStatus.Internal
public final class Playback implements ReplayPlayback {

    /** Slow enough to read a hit, fast enough to skip to the end. */
    private static final double MIN_SPEED = 1.0 / 16.0;
    private static final double MAX_SPEED = 8.0;

    private final String owner;
    private final Replay replay;
    private final Location anchor;
    private final List<Player> viewers;
    private final TaskScheduler scheduler;
    private final NpcHandle[] bodies;
    private final NpcPose[] poses;

    /**
     * Every equipment mark, already turned back into an item.
     *
     * <p>Decoded here rather than when it is passed, because the driver runs on
     * a packet thread and reading an {@link ItemStack} out of bytes is not
     * something to do off the server's own. It is a few dozen items for a whole
     * duel, done once, on the thread that asked for the playback.
     */
    private final Dressed[] dressed;

    private volatile double position;
    private volatile double speed = 1.0;
    private volatile int rendered = -1;
    private volatile boolean paused;
    private volatile boolean looping;
    private volatile boolean stopped;
    private volatile Consumer<ReplayMark> onMark;
    private volatile Runnable onEnd;

    Playback(String owner, Replay replay, Location anchor, List<Player> viewers,
             TaskScheduler scheduler) {
        this.owner = owner;
        this.replay = replay;
        this.anchor = anchor.clone();
        this.viewers = viewers;
        this.scheduler = scheduler;
        this.bodies = new NpcHandle[replay.actors().size()];
        this.poses = new NpcPose[replay.actors().size()];
        this.dressed = dress(replay);
    }

    /** Reads every equipment mark back into a slot and an item, once. */
    private static Dressed[] dress(Replay replay) {
        List<ReplayMark> marks = replay.marks();
        Dressed[] items = new Dressed[marks.size()];
        for (int index = 0; index < marks.size(); index++) {
            ReplayMark mark = marks.get(index);
            if (!ReplayMark.EQUIP.equals(mark.kind())) {
                continue;
            }
            EquipmentSlot slot = Recording.Equipment.slotOf(mark.data());
            if (slot != null) {
                items[index] = new Dressed(slot, Recording.Equipment.itemOf(mark.data()));
            }
        }
        return items;
    }

    /** One slot and what went in it, read out of a mark in advance. */
    private record Dressed(EquipmentSlot slot, ItemStack item) {
    }

    /** Which plugin's playback this is. */
    String owner() {
        return owner;
    }

    /** Whether this playback is being shown to anybody who is still here. */
    boolean hasViewers() {
        for (Player viewer : viewers) {
            if (viewer != null && viewer.isOnline()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Advances it by one tick of real time.
     *
     * <p>Called by the runtime's single driver, on a packet-sending thread.
     */
    void step() {
        if (stopped) {
            return;
        }
        if (!paused) {
            int target = (int) Math.min(position, replay.frames() - 1);
            if (target != rendered) {
                marks(rendered + 1, target);
                render(target);
                rendered = target;
            }
            position += speed;
        }
        if (position < replay.frames()) {
            return;
        }
        if (looping) {
            seek(0);
            return;
        }
        // Held on the last frame rather than taken away: the end of a duel is
        // the one moment somebody watching wants to sit on, and a playback that
        // deleted both bodies the instant it finished took it away from them.
        position = replay.frames() - 1;
        paused = true;
        Runnable end = onEnd;
        onEnd = null;
        if (end != null) {
            scheduler.run(end);
        }
    }

    @Override
    public void pause() {
        paused = true;
    }

    @Override
    public void resume() {
        if (!stopped) {
            paused = false;
        }
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void speed(double multiplier) {
        speed = Math.clamp(multiplier, MIN_SPEED, MAX_SPEED);
    }

    @Override
    public double speed() {
        return speed;
    }

    @Override
    public void seek(int tick) {
        if (stopped) {
            return;
        }
        int target = Math.clamp(tick, 0, Math.max(0, replay.frames() - 1));
        position = target;
        rendered = target;
        // Everything that was being worn at that moment, rebuilt from the
        // marks: a seek is the one place a playback cannot simply carry on from
        // what it last drew, because what it last drew may be an hour later in
        // the fight.
        for (int actor = 0; actor < bodies.length; actor++) {
            restore(actor, target);
        }
        render(target);
    }

    @Override
    public int tick() {
        return Math.max(0, rendered);
    }

    @Override
    public int frames() {
        return replay.frames();
    }

    @Override
    public void loop(boolean loop) {
        this.looping = loop;
    }

    @Override
    public @Nullable Location locationOf(@NotNull UUID actor) {
        int index = indexOf(actor);
        if (index < 0 || stopped) {
            return null;
        }
        MotionTrack track = replay.tracks().get(index);
        int at = tick();
        return track.present(at) ? placed(track, at) : null;
    }

    @Override
    public void onMark(@NotNull Consumer<ReplayMark> listener) {
        this.onMark = listener;
    }

    @Override
    public void onEnd(@NotNull Runnable listener) {
        this.onEnd = listener;
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        for (int index = 0; index < bodies.length; index++) {
            remove(index);
        }
        ReplayRuntime.forget(this);
    }

    @Override
    public boolean isPlaying() {
        return !stopped;
    }

    /** Puts every body where it was on this tick. */
    private void render(int tick) {
        for (int index = 0; index < bodies.length; index++) {
            MotionTrack track = replay.tracks().get(index);
            if (!track.present(tick)) {
                remove(index);
                continue;
            }
            NpcHandle body = bodies[index];
            if (body == null) {
                body = spawn(index, track, tick);
                if (body == null) {
                    continue;
                }
            }
            body.moveTo(placed(track, tick));
            NpcPose pose = track.pose(tick);
            if (poses[index] != pose) {
                poses[index] = pose;
                body.pose(pose);
            }
        }
    }

    /** Draws one body for the first time, dressed as it was on this tick. */
    private NpcHandle spawn(int index, MotionTrack track, int tick) {
        ReplayActor actor = replay.actors().get(index);
        NpcModel model = actor.texture() == null
                ? NpcModel.of(actor.name())
                : NpcModel.of(actor.name(), actor.texture(), actor.signature());
        NpcHandle body = NpcRuntime.showOwned(owner, model.pose(track.pose(tick)),
                placed(track, tick), viewers);
        bodies[index] = body;
        poses[index] = track.pose(tick);
        if (body != null) {
            restore(index, tick);
        }
        return body;
    }

    /** Takes one body away, if it is there. */
    private void remove(int index) {
        NpcHandle body = bodies[index];
        bodies[index] = null;
        poses[index] = null;
        if (body != null) {
            body.remove();
        }
    }

    /** Where a frame sits in the world this playback is being shown in. */
    private Location placed(MotionTrack track, int tick) {
        Location at = anchor.clone();
        at.add(track.x(tick), track.y(tick), track.z(tick));
        at.setYaw(track.yaw(tick));
        at.setPitch(track.pitch(tick));
        return at;
    }

    /**
     * Walks the marks between two ticks, drawing the ones this module owns and
     * handing the rest over.
     */
    private void marks(int from, int to) {
        if (from > to) {
            return;
        }
        Consumer<ReplayMark> listener = onMark;
        List<ReplayMark> passed = null;
        List<ReplayMark> all = replay.marks();
        for (int index = 0; index < all.size(); index++) {
            ReplayMark mark = all.get(index);
            if (mark.tick() < from) {
                continue;
            }
            if (mark.tick() > to) {
                break;
            }
            if (draw(index, mark)) {
                continue;
            }
            if (listener != null) {
                if (passed == null) {
                    passed = new ArrayList<>(2);
                }
                passed.add(mark);
            }
        }
        if (passed == null) {
            return;
        }
        // Handed over on the main thread, because whatever a plugin does with
        // one of these is almost always something Bukkit will not let it do
        // from here.
        List<ReplayMark> batch = passed;
        scheduler.run(() -> batch.forEach(listener));
    }

    /**
     * Draws one mark, if it is one of the three this module knows.
     *
     * @return whether it was drawn here and should not be handed on
     */
    private boolean draw(int index, ReplayMark mark) {
        int actor = mark.actor() == null ? -1 : indexOf(mark.actor());
        NpcHandle body = actor < 0 ? null : bodies[actor];
        switch (mark.kind()) {
            case ReplayMark.SWING -> {
                if (body != null) {
                    body.swing();
                }
                return true;
            }
            case ReplayMark.HURT -> {
                if (body != null) {
                    body.hurt();
                }
                return true;
            }
            case ReplayMark.EQUIP -> {
                apply(body, index);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Puts everything one actor was wearing at this tick back on their body. */
    private void restore(int index, int tick) {
        NpcHandle body = bodies[index];
        if (body == null) {
            return;
        }
        UUID actor = replay.actors().get(index).id();
        List<ReplayMark> all = replay.marks();
        for (int mark = 0; mark < all.size(); mark++) {
            if (all.get(mark).tick() > tick) {
                break;
            }
            if (dressed[mark] != null && actor.equals(all.get(mark).actor())) {
                apply(body, mark);
            }
        }
    }

    private void apply(NpcHandle body, int mark) {
        Dressed worn = dressed[mark];
        if (body != null && worn != null) {
            body.equip(worn.slot(), worn.item());
        }
    }

    private int indexOf(UUID actor) {
        List<ReplayActor> actors = replay.actors();
        for (int index = 0; index < actors.size(); index++) {
            if (actors.get(index).id().equals(actor)) {
                return index;
            }
        }
        return -1;
    }
}
