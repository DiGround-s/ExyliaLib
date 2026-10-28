package net.exylia.lib.guide.internal;

import net.exylia.lib.nametag.NametagStyle;
import net.exylia.lib.nametag.Nametags;
import net.exylia.lib.packet.Packets;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

/**
 * One thing outlined for one player, through whichever module already knows
 * how to do it for that kind of thing.
 *
 * <ul>
 *   <li>A player goes through the nametag module, which already keeps a
 *       per-viewer glow lit on players and owns their teams.</li>
 *   <li>Any other entity, or an entity that exists only as packets, has the
 *       glowing bit added to its flags on the way to this viewer; a colour
 *       puts it in a coloured team on that client only.</li>
 *   <li>A block goes through {@code GlowingBlocks}, which outlines it with an
 *       invisible glowing display, readable through terrain.</li>
 * </ul>
 */
final class LiveGlow extends Piece {

    private final Plugin plugin;
    private final Entity entity;
    private final int packetId;
    private final Location block;
    private final NamedTextColor colour;
    private final TextColor blockColour;

    private boolean applied;

    private LiveGlow(ViewerSession session, Plugin plugin, Entity entity, int packetId, Location block,
                     NamedTextColor colour, TextColor blockColour) {
        super(session);
        this.plugin = plugin;
        this.entity = entity;
        this.packetId = packetId;
        this.block = block;
        this.colour = colour;
        this.blockColour = blockColour;
    }

    static LiveGlow entity(ViewerSession session, Plugin plugin, Entity target, NamedTextColor colour) {
        return new LiveGlow(session, plugin, target, target.getEntityId(), null, colour, null);
    }

    static LiveGlow packetEntity(ViewerSession session, Plugin plugin, int entityId) {
        return new LiveGlow(session, plugin, null, entityId, null, null, null);
    }

    static LiveGlow block(ViewerSession session, Plugin plugin, Block target, TextColor colour) {
        return new LiveGlow(session, plugin, null, -1, target.getLocation(), null, colour);
    }

    @Override
    void draw(Player viewer, long tick) {
        if (applied) {
            return;
        }
        if (entity instanceof Player target) {
            Nametags.of(plugin).paint(viewer, target,
                    colour == null ? NametagStyle.glowOnly() : NametagStyle.of(colour).withGlow());
        } else if (entity != null) {
            if (!entity.isValid() || !entity.getWorld().equals(viewer.getWorld())) {
                return;
            }
            GuidePackets.glow(viewer.getUniqueId(), packetId, true);
            if (colour != null) {
                GuidePackets.colour(viewer, colour, entity.getUniqueId().toString(), true);
            }
            refresh(viewer);
        } else if (block != null) {
            if (!viewer.getWorld().equals(block.getWorld())) {
                return;
            }
            // The shape is read where the block lives, which on Folia may be
            // another region than the viewer's.
            session.tasks().runAtLocation(block, () -> Packets.of(plugin).glowingBlocks()
                    .show(viewer, Map.of(block, block.getBlock().getBlockData()), blockColour));
        } else {
            GuidePackets.glow(viewer.getUniqueId(), packetId, true);
            GuidePackets.flags(viewer, packetId, GuidePackets.GLOWING);
        }
        applied = true;
    }

    /** Re-sends the entity's real flags, which the listener lights or leaves dark on the way out. */
    private void refresh(Player viewer) {
        session.tasks().runAtEntity(entity, () -> GuidePackets.flags(viewer, packetId, flagsOf(entity)));
    }

    private static byte flagsOf(Entity entity) {
        int flags = 0;
        if (entity.getFireTicks() > 0) {
            flags |= 0x01;
        }
        if (entity.isGlowing()) {
            flags |= 0x40;
        }
        if (entity instanceof LivingEntity living) {
            if (living.isInvisible()) {
                flags |= 0x20;
            }
            if (living.isSwimming()) {
                flags |= 0x10;
            }
            if (living.isGliding()) {
                flags |= 0x80;
            }
        }
        return (byte) flags;
    }

    @Override
    void clear(Player viewer) {
        if (!applied) {
            return;
        }
        applied = false;
        if (entity instanceof Player target) {
            Nametags.of(plugin).reset(viewer, target);
        } else if (entity != null) {
            GuidePackets.glow(viewer.getUniqueId(), packetId, false);
            if (colour != null) {
                GuidePackets.colour(viewer, colour, entity.getUniqueId().toString(), false);
            }
            if (entity.isValid()) {
                refresh(viewer);
            }
        } else if (block != null) {
            Packets.of(plugin).glowingBlocks().clear(viewer, List.of(block));
        } else {
            GuidePackets.glow(viewer.getUniqueId(), packetId, false);
            GuidePackets.flags(viewer, packetId, (byte) 0);
        }
    }

    @Override
    void forget() {
        applied = false;
    }
}
