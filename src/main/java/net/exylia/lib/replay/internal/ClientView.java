package net.exylia.lib.replay.internal;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * What one player's own client had: how far behind the server its screen was,
 * the looks it sent, and every attack as it sent it.
 *
 * <h2>Why the server's positions are not enough</h2>
 * An attacker's client picks its target on its own screen, where everybody else
 * is drawn late by the connection and by the client's own interpolation, and
 * sends "attack entity X". The server only checks reach with its own positions.
 * A replay that shows what the attacker saw needs to know how late that screen
 * was, tick by tick.
 *
 * <h2>Seen lag, by transaction</h2>
 * While a player is in combat (dealt or took entity damage in the last
 * {@link #COMBAT_TICKS} ticks) they get a protocol ping every tick, after the
 * entity updates of the tick before; the pong says their client has drawn
 * everything up to that tick. Seen lag is now minus the newest tick answered.
 * Outside combat it is the ping's estimate and says so. The ids are
 * {@link #PING_BASE} plus a counter: Grim and most anticheats use negative
 * shorts, which an id this large is never confused with.
 *
 * <h2>Threads</h2>
 * Pongs, looks and attacks arrive on the player's Netty thread, pings leave from
 * the runtime's async driver, and the server thread only reads {@link #acked}
 * when it samples the player. Nothing here takes a lock: each field has one
 * writer, and the looks are a small ring of primitives written in place.
 */
@ApiStatus.Internal
final class ClientView {

    /** "EX" in the high half, a counter in the low: far outside the short ids anticheats use. */
    static final int PING_BASE = 0x45580000;
    private static final int PING_MASK = 0xFFFF0000;

    /** How long after the last hit somebody is still in combat: fifteen seconds. */
    static final int COMBAT_TICKS = 300;

    /** Pings remembered: an answer older than this many ticks is a connection that is not answering. */
    private static final int OUTSTANDING = 64;

    /**
     * Looks kept. The analysis reads the look sent with an attack and the turns
     * of the three ticks before it, on this same thread as the attack arrives,
     * so a short ring is all it ever reads.
     */
    // ponytail: 16 looks, read at attack time; size it to the box window if a
    // whole-fight aim trace is ever written into the recording.
    private static final int LOOKS = 16;

    static final int UNANSWERED = Integer.MIN_VALUE;

    /** Everybody the black box watches, by id. */
    private static final Map<UUID, ClientView> VIEWS = new ConcurrentHashMap<>();

    /** Attacks as sent, settled, waiting for the box to drain them. */
    static final Queue<Sent> SENT = new ConcurrentLinkedQueue<>();

    final UUID id;
    volatile int entityId;
    /** The connection pings go out on, as {@link ReplayCapture} hands it over; {@code null} without one. */
    private final @Nullable Object user;

    volatile int combatUntil = Integer.MIN_VALUE;

    // Pings: written by the pinger, read by Netty once the ping it sent is answered.
    private final int[] pingIds = new int[OUTSTANDING];
    private final int[] pingTicks = new int[OUTSTANDING];
    private int counter;
    /** The newest tick the client has answered for, or {@link #UNANSWERED}. */
    volatile int acked = UNANSWERED;
    /** The seen lag of the last sample, as {@link MotionTrack#lag} keeps it. */
    volatile int lastLag = MotionTrack.LAG_UNKNOWN;

    // Looks: Netty only.
    private final int[] lookTicks = new int[LOOKS];
    private final float[] lookYaw = new float[LOOKS];
    private final float[] lookPitch = new float[LOOKS];
    private int looks;
    private final AtomicReference<Sent> pending = new AtomicReference<>();

    private ClientView(UUID id, int entityId, @Nullable Object user) {
        this.id = id;
        this.entityId = entityId;
        this.user = user;
    }

    // ------------------------------------------------------------- registry

    /** Starts keeping a player's view; called when the box starts watching them. */
    static void open(Player player) {
        UUID id = player.getUniqueId();
        ClientView known = VIEWS.get(id);
        if (known != null) {
            known.entityId = player.getEntityId();
            return;
        }
        // Without PacketEvents there is no connection to ping: the estimate it is.
        Object user = ReplayRuntime.isSupported() ? ReplayCapture.connectionOf(player) : null;
        VIEWS.putIfAbsent(id, new ClientView(id, player.getEntityId(), user));
    }

    static @Nullable ClientView of(UUID player) {
        return VIEWS.get(player);
    }

    static void close(UUID player) {
        VIEWS.remove(player);
    }

    static void closeAll() {
        VIEWS.clear();
        SENT.clear();
    }

    /** The player with this entity id, among everybody watched; {@code null} for anything else. */
    static @Nullable UUID playerWithId(int entityId) {
        for (ClientView view : VIEWS.values()) {
            if (view.entityId == entityId) return view.id;
        }
        return null;
    }

    // --------------------------------------------------------------- combat

    /** Dealt or took entity damage, or sent an attack: pinged every tick from now. */
    void combat(int now) {
        // A new stretch of combat: the last answer is from before it and says
        // nothing about now, so the estimate stands in until the first pong.
        if (now > combatUntil) acked = UNANSWERED;
        combatUntil = now + COMBAT_TICKS;
    }

    static void combat(UUID player, int now) {
        ClientView view = VIEWS.get(player);
        if (view != null) view.combat(now);
    }

    /**
     * The seen lag for this tick's sample, read on the server thread: field
     * reads and the ping the server already keeps, nothing else.
     */
    int lag(int now, Player player) {
        int answered = acked;
        int seen;
        if (now <= combatUntil && answered != UNANSWERED) {
            seen = Math.clamp(now - answered, 0, 254);
        } else {
            seen = MotionTrack.LAG_ESTIMATED | Math.clamp(player.getPing() / 50 + 1, 0, 254);
        }
        lastLag = seen;
        return seen;
    }

    // ---------------------------------------------------------------- pings

    /** Pings everybody in combat; run by the runtime's async driver once a tick. */
    static void pingAll(int now) {
        if (VIEWS.isEmpty()) return;
        for (ClientView view : VIEWS.values()) view.ping(now);
    }

    private void ping(int now) {
        Object connection = user;
        if (connection == null || now > combatUntil) return;
        int pingId = PING_BASE | (++counter & 0xFFFF);
        int slot = pingId & (OUTSTANDING - 1);
        pingIds[slot] = pingId;
        // Sent once the tick before has written its entity updates: an answer
        // means the client has drawn everything up to that tick.
        pingTicks[slot] = now - 1;
        ReplayCapture.ping(connection, pingId);
    }

    /** A pong. Ids outside the range are somebody else's and left alone. */
    void pong(int pingId) {
        if ((pingId & PING_MASK) != PING_BASE) return;
        int slot = pingId & (OUTSTANDING - 1);
        if (pingIds[slot] != pingId) return;
        int tick = pingTicks[slot];
        if (tick > acked) acked = tick;
    }

    // ---------------------------------------------------------------- looks

    /** A look the client sent. */
    void look(int now, float yaw, float pitch) {
        int at = looks & (LOOKS - 1);
        lookTicks[at] = now;
        lookYaw[at] = yaw;
        lookPitch[at] = pitch;
        looks++;
        settle();
    }

    /**
     * An attack as sent. Its look comes after it: the client picks its target
     * and sends the attack early in its tick, and the look it picked with at
     * the end of the same tick, so the attack waits for the next look, the
     * client's tick ending or the next attack, whichever comes first.
     */
    void attack(int now, int target) {
        settle();
        combat(now);
        pending.set(new Sent(id, target, now, lastLag, looks));
    }

    /** Settles the attack waiting for its look, if any. */
    void settle() {
        Sent attack = pending.getAndSet(null);
        if (attack == null) return;
        int newest = looks - 1;
        // The look sent right after it, in the same tick, is the one it was
        // aimed with; one arriving later is the next tick's, and the look
        // before the attack is the better guess then.
        int use = newest >= attack.looksBefore && newest >= 0
                && lookTicks[newest & (LOOKS - 1)] - attack.tick <= 1 ? newest : attack.looksBefore - 1;
        if (use >= 0 && use > newest - LOOKS) {
            int at = use & (LOOKS - 1);
            attack.yaw = lookYaw[at];
            attack.pitch = lookPitch[at];
            attack.sent = true;
            attack.flick = flick(use, attack.tick);
        }
        SENT.offer(attack);
    }

    /** Settles one that has waited too long for a look; the server thread's sample asks. */
    void settleStale(int now) {
        Sent attack = pending.get();
        if (attack != null && now - attack.tick > 2) settle();
    }

    /** The largest turn between two looks, in degrees, over the three ticks up to this one. */
    private float flick(int upTo, int tick) {
        float largest = 0f;
        for (int index = upTo; index > upTo - LOOKS + 1 && index > 0; index--) {
            int at = index & (LOOKS - 1);
            if (lookTicks[at] < tick - 3) break;
            int before = (index - 1) & (LOOKS - 1);
            float yaw = ((lookYaw[at] - lookYaw[before]) % 360f + 540f) % 360f - 180f;
            float pitch = lookPitch[at] - lookPitch[before];
            largest = Math.max(largest, (float) Math.sqrt(yaw * yaw + pitch * pitch));
        }
        return largest;
    }

    /** Whether an attack is waiting for its look. */
    boolean waiting() {
        return pending.get() != null;
    }

    /** One attack as the client sent it, settled once its look is known. */
    static final class Sent {

        final UUID attacker;
        final int target;
        final int tick;
        final int lag;
        /** How many looks had arrived when it did. */
        final int looksBefore;
        float yaw = Float.NaN;
        float pitch = Float.NaN;
        float flick = Float.NaN;
        /** Whether a look was found for it. */
        boolean sent;
        /** The target's id, worked out when the box drains it. */
        UUID victim;

        Sent(UUID attacker, int target, int tick, int lag, int looksBefore) {
            this.attacker = attacker;
            this.target = target;
            this.tick = tick;
            this.lag = lag;
            this.looksBefore = looksBefore;
        }
    }
}
