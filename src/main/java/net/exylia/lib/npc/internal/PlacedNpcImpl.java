package net.exylia.lib.npc.internal;

import net.exylia.lib.interaction.Click;
import net.exylia.lib.npc.NpcLook;
import net.exylia.lib.npc.NpcSkin;
import net.exylia.lib.npc.NpcSpec;
import net.exylia.lib.npc.PlacedNpc;
import net.exylia.lib.task.Tasks;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * One placed NPC: where it stands, what it wears, and what every viewer has been sent.
 *
 * <p>Everything that sends packets holds this object's monitor, so the visibility pass
 * and a caller moving or changing it from another thread never interleave half-sent
 * states. A click only reads.
 */
final class PlacedNpcImpl implements PlacedNpc {

    /** One press can arrive twice; anything closer than this is the same press. */
    static final long CLICK_GAP_MS = 200L;

    /** Where a standing player's eyes are, one being a player's own size. */
    static final double EYE_HEIGHT = 1.62;

    /** A turn smaller than this, in degrees, is not worth a packet. */
    static final float TURN_EPSILON = 1.5f;

    private final String id;
    private final Plugin owner;
    private final int entityId;
    private final UUID profile = UUID.randomUUID();
    /**
     * The name it is announced and teamed under. Generated rather than chosen, so the
     * team that hides it can never take in a real player of the same name.
     */
    private final String name;

    private volatile Location location;
    private volatile NpcSpec spec;
    private volatile Predicate<Player> filter;
    private volatile BiConsumer<Player, Click> handler;
    private volatile boolean removed;

    private final Map<UUID, Viewer> viewers = new ConcurrentHashMap<>();

    /** What one player has been sent. */
    private static final class Viewer {
        final Player player;
        final World world;
        volatile boolean drawn;
        float yaw;
        float pitch;
        long lastClick = Long.MIN_VALUE / 2;

        Viewer(Player player) {
            this.player = player;
            this.world = player.getWorld();
        }
    }

    PlacedNpcImpl(String id, Plugin owner, int entityId, NpcSpec spec, Location location) {
        this.id = id;
        this.owner = owner;
        this.entityId = entityId;
        this.name = "npc_" + Integer.toHexString(entityId);
        this.spec = spec;
        this.location = location.clone();
    }

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    @Override
    public @NotNull String id() {
        return id;
    }

    @Override
    public @NotNull Location location() {
        return location.clone();
    }

    @Override
    public @NotNull NpcSpec spec() {
        return spec;
    }

    @Override
    public void onClick(@Nullable BiConsumer<Player, Click> handler) {
        this.handler = handler;
    }

    @Override
    public synchronized void visibleIf(@Nullable Predicate<Player> filter) {
        this.filter = filter;
        for (Viewer viewer : List.copyOf(viewers.values())) {
            if (!canSee(viewer.player, location, spec)) {
                despawn(viewer.player);
            }
        }
    }

    @Override
    public synchronized void moveTo(@NotNull Location to) {
        Location from = location;
        location = to.clone();
        if (removed) {
            return;
        }
        if (from.getWorld() != to.getWorld()) {
            despawnAll();
            return;
        }
        for (Viewer viewer : viewers.values()) {
            if (viewer.drawn) {
                sink().teleport(viewer.player, entityId, location);
                viewer.yaw = to.getYaw();
                viewer.pitch = to.getPitch();
            }
        }
    }

    @Override
    public synchronized void update(@NotNull NpcSpec next) {
        NpcSpec before = spec;
        spec = Objects.requireNonNull(next, "spec");
        if (removed) {
            return;
        }
        if (respawns(before, next)) {
            despawnAll();
            return;
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack was = before.equipment().get(slot);
            ItemStack now = next.equipment().get(slot);
            if (!Objects.equals(was, now)) {
                forDrawn(viewer -> sink().equip(viewer, entityId, slot, now));
            }
        }
        if (before.pose() != next.pose()) {
            forDrawn(viewer -> sink().pose(viewer, entityId, next.pose()));
        }
    }

    @Override
    public synchronized void swing() {
        forDrawn(viewer -> sink().swing(viewer, entityId));
    }

    @Override
    public synchronized void hurt() {
        forDrawn(viewer -> sink().hurt(viewer, entityId));
    }

    @Override
    public boolean isViewing(@NotNull Player player) {
        Viewer viewer = viewers.get(player.getUniqueId());
        return viewer != null && viewer.drawn;
    }

    @Override
    public int viewerCount() {
        int count = 0;
        for (Viewer viewer : viewers.values()) {
            if (viewer.drawn) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void remove() {
        PlacedRuntime.remove(this);
    }

    @Override
    public boolean removed() {
        return removed;
    }

    // ------------------------------------------------------------------
    // Engine, driven by PlacedRuntime
    // ------------------------------------------------------------------

    int entityId() {
        return entityId;
    }

    Plugin owner() {
        return owner;
    }

    /** Whether a change of spec only reaches a viewer through a new spawn. */
    static boolean respawns(NpcSpec before, NpcSpec after) {
        return !before.skin().equals(after.skin())
                || before.scale() != after.scale()
                || before.glow() != after.glow();
    }

    /** Whether a player should have it on screen. */
    private boolean canSee(Player player, Location at, NpcSpec spec) {
        if (removed || !player.isOnline() || player.getWorld() != at.getWorld()) {
            return false;
        }
        Predicate<Player> current = filter;
        if (current != null && !current.test(player)) {
            return false;
        }
        double range = spec.viewDistance();
        return player.getLocation().distanceSquared(at) <= range * range;
    }

    /**
     * One visibility pass: announce to who came in range, draw for who was announced to
     * on the previous pass, take it back from who left, and turn the head.
     *
     * <p>The body is drawn a pass after the identity on purpose: a client that has not
     * processed the player entry yet when the spawn arrives draws nothing at all.
     */
    synchronized void pass(List<? extends Player> online) {
        if (removed) {
            return;
        }
        NpcSpec current = spec;
        Location at = location;
        Set<UUID> seen = new HashSet<>(online.size() * 2);
        for (Player player : online) {
            UUID uuid = player.getUniqueId();
            seen.add(uuid);
            Viewer viewer = viewers.get(uuid);
            if (viewer != null && viewer.world != player.getWorld()) {
                // The client dropped every entity when it changed worlds.
                despawn(player);
                viewer = null;
            }
            boolean should = canSee(player, at, current);
            if (should && viewer == null) {
                announce(player, current);
            } else if (should && !viewer.drawn) {
                draw(viewer, current, at);
            } else if (!should && viewer != null) {
                despawn(player);
            }
        }
        // Somebody who left without the quit reaching us: nothing to send.
        viewers.keySet().removeIf(uuid -> !seen.contains(uuid));
        turn(current, at);
    }

    private void announce(Player player, NpcSpec current) {
        viewers.put(player.getUniqueId(), new Viewer(player));
        sink().announce(player, profile, name, PlacedRuntime.skinFor(current.skin(), player),
                current.glow());
    }

    private void draw(Viewer viewer, NpcSpec current, Location at) {
        viewer.drawn = true;
        viewer.yaw = at.getYaw();
        viewer.pitch = at.getPitch();
        sink().draw(viewer.player, entityId, profile, name, current, at, at.getYaw(), at.getPitch());
    }

    /** Turns the head for every viewer whose target angle moved enough to see. */
    private void turn(NpcSpec current, Location at) {
        NpcLook look = current.look();
        double rangeSquared = current.lookRange() * current.lookRange();
        Player nearest = null;
        if (look == NpcLook.NEAREST) {
            double best = rangeSquared;
            for (Viewer viewer : viewers.values()) {
                double distance = viewer.player.getLocation().distanceSquared(at);
                if (viewer.drawn && distance <= best) {
                    best = distance;
                    nearest = viewer.player;
                }
            }
        }
        for (Viewer viewer : viewers.values()) {
            if (!viewer.drawn) {
                continue;
            }
            Player target = switch (look) {
                case FIXED -> null;
                case NEAREST -> nearest;
                case EACH_VIEWER -> viewer.player.getLocation().distanceSquared(at) <= rangeSquared
                        ? viewer.player : null;
            };
            float yaw = at.getYaw();
            float pitch = at.getPitch();
            if (target != null) {
                float[] angles = angles(at, EYE_HEIGHT * current.scale(), target.getLocation());
                yaw = angles[0];
                pitch = angles[1];
            }
            if (Math.abs(wrap(yaw - viewer.yaw)) > TURN_EPSILON
                    || Math.abs(pitch - viewer.pitch) > TURN_EPSILON) {
                viewer.yaw = yaw;
                viewer.pitch = pitch;
                sink().look(viewer.player, entityId, yaw, pitch);
            }
        }
    }

    /**
     * The yaw and pitch that point eyes at {@code from} towards a player's eyes at
     * {@code target}, in Minecraft's degrees.
     */
    static float[] angles(Location from, double eyeHeight, Location target) {
        double dx = target.getX() - from.getX();
        double dy = (target.getY() + EYE_HEIGHT) - (from.getY() + eyeHeight);
        double dz = target.getZ() - from.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[] {yaw, pitch};
    }

    /** An angle folded into -180..180. */
    private static float wrap(float degrees) {
        float folded = degrees % 360f;
        if (folded >= 180f) {
            folded -= 360f;
        } else if (folded < -180f) {
            folded += 360f;
        }
        return folded;
    }

    /** A viewer clicked the body. Arrives on the network thread. */
    void clicked(Player player, boolean attack, long now) {
        if (removed) {
            return;
        }
        Viewer viewer = viewers.get(player.getUniqueId());
        if (viewer == null || !viewer.drawn) {
            return;
        }
        synchronized (viewer) {
            if (now - viewer.lastClick < CLICK_GAP_MS) {
                return;
            }
            viewer.lastClick = now;
        }
        BiConsumer<Player, Click> current = handler;
        if (current == null) {
            return;
        }
        Click click = Click.of(attack, player.isSneaking());
        Tasks.of(owner).runAtEntity(player, () -> current.accept(player, click));
    }

    /** Takes it off one player's screen. */
    synchronized void despawn(Player player) {
        if (viewers.remove(player.getUniqueId()) != null) {
            sink().destroy(player, entityId, profile, name);
        }
    }

    /** Takes it off every screen; the next pass sends it again to whoever should see it. */
    synchronized void despawnAll() {
        for (Viewer viewer : List.copyOf(viewers.values())) {
            despawn(viewer.player);
        }
    }

    /** Gives every viewer the NPC again, for a skin that just arrived. */
    synchronized void reskin(String account) {
        NpcSkin skin = spec.skin();
        if (skin.playerName() != null && skin.playerName().equalsIgnoreCase(account)) {
            despawnAll();
        }
    }

    /** Forgets a player who left, without sending them anything. */
    void forget(UUID player) {
        viewers.remove(player);
    }

    /** Stops it for good; the caller has taken it off every screen. */
    void markRemoved() {
        removed = true;
    }

    private void forDrawn(java.util.function.Consumer<Player> action) {
        for (Viewer viewer : viewers.values()) {
            if (viewer.drawn) {
                action.accept(viewer.player);
            }
        }
    }

    private static PlacedSink sink() {
        return PlacedRuntime.sink();
    }
}
