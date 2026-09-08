package net.exylia.lib.packet.internal;

import net.exylia.lib.packet.GlowingBlocks;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One plugin's block outlines.
 *
 * <p>A class of its own rather than another face of {@code PacketRuntime.Impl}:
 * {@link GlowingBlocks} and {@link net.exylia.lib.packet.FakeBlocks} both
 * declare {@code clear(Player)}, and one object cannot answer that call two
 * ways.
 *
 * <p>What each viewer has on screen lives in {@link PacketRuntime#OUTLINED},
 * so a quit or a world change forgets it with everything else.
 */
final class BlockOutlines implements GlowingBlocks {

    private final Plugin plugin;

    BlockOutlines(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Outlines drawn for one viewer in one tick.
     *
     * <p>Every outline is two packets — a display entity and its metadata — and
     * a caller that asks for a couple of thousand of them is asking for a
     * couple of thousand entities to appear at once. Sent whole, that is a tick
     * spent writing packets while nothing else on the server runs; a slice at a
     * time, a full x-ray sweep finishes inside a second and no tick notices it
     * happened.
     */
    private static final int PER_TICK = 128;

    /**
     * What is still to be drawn for each viewer, and the one task draining it.
     *
     * <p>The budget belongs to the viewer rather than to the call. An x-ray
     * sweep is one call per chunk per colour, and a viewer walking into range of
     * fifty chunks used to start fifty independent drawings that each thought
     * they had a whole tick's budget to themselves.
     */
    private final Map<UUID, Queue<Outline>> pending = new ConcurrentHashMap<>();

    /** The viewers a drawing is already running for, so a second call joins it. */
    private final Set<UUID> draining = ConcurrentHashMap.newKeySet();

    /** One block waiting to be outlined. */
    private record Outline(Location at, BlockData data, int argb) {
    }

    @Override
    public void show(@NotNull Player viewer, @NotNull Map<Location, BlockData> blocks,
                     @NotNull TextColor colour) {
        PacketSink out = PacketRuntime.sink();
        if (out == null || blocks.isEmpty()) {
            return;
        }
        PacketRuntime.OUTLINED.computeIfAbsent(viewer.getUniqueId(),
                ignored -> new ConcurrentHashMap<>());
        int argb = 0xFF000000 | colour.value();
        UUID viewerId = viewer.getUniqueId();

        // Copied out of the caller's map: what is drawn is what was asked for at
        // this moment, whatever the caller does with its map afterwards.
        Queue<Outline> queue = pending.computeIfAbsent(viewerId,
                ignored -> new ConcurrentLinkedQueue<>());
        for (Map.Entry<Location, BlockData> entry : blocks.entrySet()) {
            queue.add(new Outline(entry.getKey(), entry.getValue(), argb));
        }
        if (draining.add(viewerId)) {
            begin(viewer, viewerId, queue);
        }
    }

    /**
     * Draws a viewer's waiting outlines, a slice a tick.
     *
     * <p>The first slice goes out now rather than next tick, so an outline small
     * enough to fit in one appears exactly as immediately as it always did.
     */
    private void begin(Player viewer, UUID viewerId, Queue<Outline> queue) {
        Tasks.of(plugin).runAtEntity(viewer, () -> {
            if (!slice(viewer, viewerId, queue)) {
                return;
            }
            Tasks.of(plugin).runAtEntityTimer(viewer, 1L, 1L, handle -> {
                if (!slice(viewer, viewerId, queue)) {
                    handle.cancel();
                }
            });
        });
    }

    /**
     * Draws up to one tick's worth.
     *
     * @return whether there is more left to draw
     */
    private boolean slice(Player viewer, UUID viewerId, Queue<Outline> queue) {
        Map<Location, Integer> drawn = PacketRuntime.OUTLINED.get(viewerId);
        PacketSink out = PacketRuntime.sink();
        // Stopped as soon as the viewer is gone or something cleared their
        // outlines: what is left of a sweep nobody is watching is not drawn, and
        // an entity spawned after a clear is one nothing remembers.
        if (out == null || drawn == null || !viewer.isOnline()) {
            pending.remove(viewerId);
            draining.remove(viewerId);
            return false;
        }
        for (int done = 0; done < PER_TICK; done++) {
            Outline outline = queue.poll();
            if (outline == null) {
                draining.remove(viewerId);
                // Somebody asked for more between the last poll and here; they
                // saw a drawing already running and left it to this one.
                return !queue.isEmpty() && draining.add(viewerId);
            }
            draw(out, viewer, drawn, outline);
        }
        return true;
    }

    /** Draws one outline, on the viewer's thread. */
    private void draw(PacketSink out, Player viewer, Map<Location, Integer> drawn, Outline outline) {
        Location at = outline.at();
        if (at.getWorld() == null || !at.getWorld().equals(viewer.getWorld())) {
            return;
        }
        Location key = at.getBlock().getLocation();
        int id = out.newEntityId();
        // The record first: showing the same position twice must not leave an
        // entity behind that nothing remembers how to remove.
        if (drawn.putIfAbsent(key, id) != null) {
            return;
        }
        out.glowingBlock(viewer, id, key, outline.data(), outline.argb());
    }

    @Override
    public void clear(@NotNull Player viewer) {
        // The queue too: what has not been drawn yet must not appear after the
        // clear that was meant to take it off the screen.
        Queue<Outline> waiting = pending.get(viewer.getUniqueId());
        if (waiting != null) {
            waiting.clear();
        }
        Map<Location, Integer> drawn = PacketRuntime.OUTLINED.remove(viewer.getUniqueId());
        if (drawn != null) {
            despawn(viewer, drawn.values());
        }
    }

    @Override
    public void clear(@NotNull Player viewer, @NotNull Collection<Location> positions) {
        Map<Location, Integer> drawn = PacketRuntime.OUTLINED.get(viewer.getUniqueId());
        if (drawn == null) {
            return;
        }
        List<Integer> gone = new ArrayList<>();
        for (Location at : positions) {
            Integer id = drawn.remove(at.getBlock().getLocation());
            if (id != null) {
                gone.add(id);
            }
        }
        despawn(viewer, gone);
    }

    private void despawn(Player viewer, Collection<Integer> ids) {
        PacketSink out = PacketRuntime.sink();
        if (out == null || ids.isEmpty() || !viewer.isOnline()) {
            return;
        }
        int[] entityIds = new int[ids.size()];
        int index = 0;
        for (int id : ids) {
            entityIds[index++] = id;
        }
        Tasks.of(plugin).runAtEntity(viewer, () -> out.destroyEntities(viewer, entityIds));
    }
}
