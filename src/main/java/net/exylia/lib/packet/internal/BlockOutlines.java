package net.exylia.lib.packet.internal;

import net.exylia.lib.packet.GlowingBlocks;
import net.exylia.lib.task.Tasks;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    @Override
    public void show(@NotNull Player viewer, @NotNull Map<Location, TextColor> blocks) {
        PacketSink out = PacketRuntime.sink();
        if (out == null || blocks.isEmpty()) {
            return;
        }
        Map<Location, Integer> drawn = PacketRuntime.OUTLINED.computeIfAbsent(viewer.getUniqueId(),
                ignored -> new ConcurrentHashMap<>());
        Tasks.of(plugin).runAtEntity(viewer, () -> {
            for (Map.Entry<Location, TextColor> entry : blocks.entrySet()) {
                Location at = entry.getKey();
                if (at.getWorld() == null || !at.getWorld().equals(viewer.getWorld())) {
                    continue;
                }
                Location key = at.getBlock().getLocation();
                int id = out.newEntityId();
                // The record first: showing the same position twice must not
                // leave an entity behind that nothing remembers how to remove.
                if (drawn.putIfAbsent(key, id) != null) {
                    continue;
                }
                out.glowingBlock(viewer, id, key, key.getBlock().getBlockData(),
                        0xFF000000 | entry.getValue().value());
            }
        });
    }

    @Override
    public void clear(@NotNull Player viewer) {
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
