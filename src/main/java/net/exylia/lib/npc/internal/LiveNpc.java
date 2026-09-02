package net.exylia.lib.npc.internal;

import net.exylia.lib.npc.NpcHandle;
import net.exylia.lib.npc.NpcModel;
import net.exylia.lib.npc.NpcPose;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * One NPC that is currently on somebody's screen.
 *
 * <p>Holds where it stands and when it goes, and nothing else. The driver walks
 * these once a second and asks each one whether its time is up, which is a
 * comparison of two longs.
 */
final class LiveNpc implements NpcHandle {

    private final String owner;
    private final int entityId;
    private final NpcModel model;
    private final List<Player> viewers;
    private final Location at;
    private final long endsAt;

    private volatile boolean gone;

    LiveNpc(String owner, int entityId, NpcModel model, List<Player> viewers,
            Location at, long now, long lifeMillis) {
        this.owner = owner;
        this.entityId = entityId;
        this.model = model;
        this.viewers = viewers;
        this.at = at;
        this.endsAt = now + lifeMillis;
    }

    /** Which plugin's effect this belongs to. */
    String owner() {
        return owner;
    }

    void spawn(NpcSink sink) {
        sink.spawn(viewers, entityId, model, at);
    }

    /**
     * Says whether this NPC is finished, taking it away if it is.
     *
     * @param sink where packets go
     * @param now  the current time
     * @return whether it has been removed and should be forgotten
     */
    boolean expired(NpcSink sink, long now) {
        if (gone) {
            return true;
        }
        if (now < endsAt) {
            return false;
        }
        destroy(sink);
        return true;
    }

    /** Takes it off every client, once. */
    void destroy(NpcSink sink) {
        if (gone) {
            return;
        }
        gone = true;
        sink.destroy(viewers, entityId, model.id());
    }

    @Override
    public void remove() {
        NpcRuntime.remove(this);
    }

    @Override
    public boolean isShowing() {
        return !gone;
    }

    @Override
    public void look(float yaw, float pitch) {
        if (!gone) {
            NpcRuntime.sink().look(viewers, entityId, yaw, pitch);
        }
    }

    @Override
    public void lookAt(@NotNull Location target) {
        if (gone || target.getWorld() == null || !target.getWorld().equals(at.getWorld())) {
            return;
        }
        double dx = target.getX() - at.getX();
        double dy = target.getY() - at.getY();
        double dz = target.getZ() - at.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, flat));
        look(yaw, pitch);
    }

    @Override
    public void pose(@NotNull NpcPose pose) {
        if (!gone) {
            NpcRuntime.sink().pose(viewers, entityId, model, pose);
        }
    }
}
