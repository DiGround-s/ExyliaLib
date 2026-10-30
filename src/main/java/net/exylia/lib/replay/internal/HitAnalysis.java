package net.exylia.lib.replay.internal;

import net.exylia.lib.replay.Replay;
import net.exylia.lib.replay.ReplayActor;
import net.exylia.lib.replay.ReplayHit;
import net.exylia.lib.replay.ReplayMark;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out what an attack looked like from the attacker's side.
 *
 * <p>Run once, when a capture is cut, on the thread putting it together; and
 * for a recording that never had it (an older one, a recorder's), when its hits
 * are asked for. Only the recording is read: positions, poses and seen lag from
 * the tracks, the look from the attack mark when the client's own was caught.
 *
 * <h2>Which tick is which</h2>
 * An attack is handled between two ticks and stamped with the first; the
 * sample of the next is the first taken after it, and is where the attacker
 * and, for the server, the target stood. The attacker's screen had the target
 * where the sample {@code seenLag} ticks before the stamp put it.
 */
@ApiStatus.Internal
public final class HitAnalysis {

    /** The client's interpolation: the bodies it shows are this far behind what it was sent. */
    static final int INTERPOLATION = 3;

    private HitAnalysis() {
    }

    /** Every attack mark of a recording with its analysis written in. */
    static List<ReplayMark> analysed(Replay replay) {
        List<ReplayMark> marks = new ArrayList<>(replay.marks().size());
        for (ReplayMark mark : replay.marks()) {
            if (ReplayMark.ATTACK.equals(mark.kind()) && mark.actor() != null) {
                MarkData.Hit hit = analyse(replay, mark);
                if (hit != null && hit.analysed()) {
                    marks.add(new ReplayMark(mark.tick(), mark.kind(), mark.actor(), hit.write()));
                    continue;
                }
            }
            marks.add(mark);
        }
        return marks;
    }

    /** The hits of a recording, analysed where the recording was not. */
    public static List<ReplayHit> hits(Replay replay) {
        List<ReplayHit> hits = new ArrayList<>();
        for (ReplayMark mark : replay.marks()) {
            if (!ReplayMark.ATTACK.equals(mark.kind()) || mark.actor() == null) continue;
            MarkData.Hit hit = analyse(replay, mark);
            if (hit == null) continue;
            hits.add(new ReplayHit(mark.actor(), hit.victim(), mark.tick(), hit.reachServer(), hit.reachSeen(),
                    hit.angleOff(), hit.flick(), hit.seenLag(), hit.estimated(), hit.damage(),
                    (hit.how() & MarkData.UNCONFIRMED) == 0));
        }
        return hits;
    }

    /** One attack, analysed; {@code null} for a mark without a victim. */
    static @Nullable MarkData.Hit analyse(Replay replay, ReplayMark mark) {
        MarkData.Hit hit = MarkData.Hit.read(mark.data());
        if (hit == null || hit.analysed() || mark.actor() == null) return hit;
        int attacker = indexOf(replay, mark.actor());
        int victim = indexOf(replay, hit.victim());
        if (attacker < 0 || victim < 0) return hit;
        MotionTrack from = replay.tracks().get(attacker);
        MotionTrack to = replay.tracks().get(victim);
        int tick = mark.tick();
        int after = from.present(tick + 1) && to.present(tick + 1)
                && replay.sceneAt(tick + 1) == replay.sceneAt(tick) ? tick + 1 : tick;
        if (!from.present(after) || !to.present(after)) return hit;

        int lag = hit.seenLag();
        boolean estimated = hit.estimated();
        if (lag < 0) {
            lag = from.seenLag(tick);
            estimated = from.lagEstimated(tick);
        }
        if (lag < 0) {
            lag = pingLag(replay, mark.actor(), tick);
            estimated = true;
        }

        float yaw = hit.sent() ? hit.yaw() : from.yaw(after);
        float pitch = hit.sent() ? hit.pitch() : from.pitch(after);
        String pose = from.poseName(after);
        double ex = from.x(after);
        double ey = from.y(after) + eyeHeight(pose);
        double ez = from.z(after);

        float reachServer = Float.NaN;
        float reachSeen = Float.NaN;
        float angleOff = Float.NaN;
        ReplayActor target = replay.actors().get(victim);
        if (target.isPlayer()) {
            double[] server = box(to, after);
            reachServer = (float) distance(ex, ey, ez, server);
            int seenTick = lag >= 0 ? tick - lag : -1;
            double[] seen = seenTick >= 0 && to.present(seenTick)
                    && replay.sceneAt(seenTick) == replay.sceneAt(after) ? box(to, seenTick) : null;
            if (seen != null) reachSeen = (float) distance(ex, ey, ez, seen);
            angleOff = (float) angleOff(ex, ey, ez, yaw, pitch, seen != null ? seen : server);
        }
        float flick = Float.isNaN(hit.flick()) ? trackFlick(from, tick) : hit.flick();
        return new MarkData.Hit(hit.victim(), hit.how(), hit.damage(), hit.sent(), yaw, pitch, lag, estimated,
                reachServer, reachSeen, angleOff, flick);
    }

    /** Seen lag from the ping written once a second: half the round trip there and back is not it, the whole is. */
    static int pingLag(Replay replay, java.util.UUID actor, int tick) {
        ReplayMark ping = replay.last(ReplayMark.PING, actor, tick);
        if (ping == null || ping.data() == null) return -1;
        try {
            int millis = Integer.parseInt(new String(ping.data(), StandardCharsets.UTF_8).trim());
            return Math.clamp(millis / 50 + 1, 0, 254);
        } catch (NumberFormatException unreadable) {
            return -1;
        }
    }

    /** Where a player's eyes are above their feet, by pose. */
    static double eyeHeight(String pose) {
        return switch (pose) {
            case "CROUCHING" -> 1.27;
            case "SWIMMING", "FALL_FLYING", "SPIN_ATTACK" -> 0.4;
            case "SLEEPING", "DYING" -> 0.2;
            default -> 1.62;
        };
    }

    /** A player's hitbox on a tick, by pose: min x, y, z, max x, y, z. */
    static double[] box(MotionTrack track, int tick) {
        String pose = track.poseName(tick);
        double width;
        double height;
        switch (pose) {
            case "CROUCHING" -> {
                width = 0.6;
                height = 1.5;
            }
            case "SWIMMING", "FALL_FLYING", "SPIN_ATTACK" -> {
                width = 0.6;
                height = 0.6;
            }
            case "SLEEPING", "DYING" -> {
                width = 0.2;
                height = 0.2;
            }
            default -> {
                width = 0.6;
                height = 1.8;
            }
        }
        double x = track.x(tick);
        double y = track.y(tick);
        double z = track.z(tick);
        double half = width / 2;
        return new double[] {x - half, y, z - half, x + half, y + height, z + half};
    }

    /** From a point to the nearest point of a box, 0 inside it: what the server's reach check measures. */
    static double distance(double x, double y, double z, double[] box) {
        double dx = Math.max(Math.max(box[0] - x, 0), x - box[3]);
        double dy = Math.max(Math.max(box[1] - y, 0), y - box[4]);
        double dz = Math.max(Math.max(box[2] - z, 0), z - box[5]);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Degrees between a look and a box: 0 when the ray goes through it,
     * otherwise the smallest angle to a point of it, searched along the ray
     * (the nearest point of the box to each point of the ray), which is within
     * a few hundredths of a degree for anything in reach.
     */
    static double angleOff(double ex, double ey, double ez, float yaw, float pitch, double[] box) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * Math.cos(pitchRad);
        if (hits(ex, ey, ez, dx, dy, dz, box)) return 0.0;
        double cx = (box[0] + box[3]) / 2 - ex;
        double cy = (box[1] + box[4]) / 2 - ey;
        double cz = (box[2] + box[5]) / 2 - ez;
        double far = Math.sqrt(cx * cx + cy * cy + cz * cz) + 2.0;
        double best = Math.PI;
        int steps = 400;
        for (int step = 0; step <= steps; step++) {
            double t = far * step / steps;
            double px = Math.clamp(ex + dx * t, box[0], box[3]) - ex;
            double py = Math.clamp(ey + dy * t, box[1], box[4]) - ey;
            double pz = Math.clamp(ez + dz * t, box[2], box[5]) - ez;
            double length = Math.sqrt(px * px + py * py + pz * pz);
            if (length < 1e-9) return 0.0;
            double cos = (px * dx + py * dy + pz * dz) / length;
            best = Math.min(best, Math.acos(Math.clamp(cos, -1.0, 1.0)));
        }
        return Math.toDegrees(best);
    }

    /** Whether a ray from a point goes through a box, forwards only. */
    static boolean hits(double ex, double ey, double ez, double dx, double dy, double dz, double[] box) {
        double near = 0;
        double far = Double.MAX_VALUE;
        double[] origin = {ex, ey, ez};
        double[] direction = {dx, dy, dz};
        for (int axis = 0; axis < 3; axis++) {
            double low = box[axis];
            double high = box[axis + 3];
            if (Math.abs(direction[axis]) < 1e-12) {
                if (origin[axis] < low || origin[axis] > high) return false;
                continue;
            }
            double t1 = (low - origin[axis]) / direction[axis];
            double t2 = (high - origin[axis]) / direction[axis];
            near = Math.max(near, Math.min(t1, t2));
            far = Math.min(far, Math.max(t1, t2));
            if (near > far) return false;
        }
        return true;
    }

    /** The largest turn between two samples over the three ticks up to the attack, from the track. */
    private static float trackFlick(MotionTrack track, int tick) {
        float largest = 0f;
        for (int at = tick - 2; at <= tick + 1; at++) {
            if (!track.present(at) || !track.present(at - 1)) continue;
            float yaw = ((track.yaw(at) - track.yaw(at - 1)) % 360f + 540f) % 360f - 180f;
            float pitch = track.pitch(at) - track.pitch(at - 1);
            largest = Math.max(largest, (float) Math.sqrt(yaw * yaw + pitch * pitch));
        }
        return largest;
    }

    private static int indexOf(Replay replay, java.util.UUID actor) {
        List<ReplayActor> actors = replay.actors();
        for (int index = 0; index < actors.size(); index++) {
            if (actors.get(index).id().equals(actor)) return index;
        }
        return -1;
    }
}
