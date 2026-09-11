package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.display.internal.DisplayRuntime;
import net.exylia.lib.ragdoll.RagdollMotion;
import net.exylia.lib.ragdoll.RagdollModel;
import net.exylia.lib.ragdoll.RagdollPart;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gives every piece of a body something to be drawn with, and puts it on screen.
 *
 * <h2>Nothing is moved, everything is told</h2>
 * The whole flight of every piece is solved by {@link RagdollPieces}, once, the
 * moment the body goes. The client is then handed those poses and draws every
 * frame in between at its own frame rate.
 *
 * <p>That is the difference between this and an NPC dragged about by teleport
 * packets. A teleported body moves once a tick at best, stutters whenever the
 * server does, and cannot rotate smoothly at all. A body solved in advance is
 * as smooth as the player's monitor, on a server at five ticks a second, and
 * costs a fraction of the packets.
 *
 * <h2>The simulation is deliberately not physics</h2>
 * Pieces are points with a bounce and no collision but the floor they died on.
 * A body that flies through a wall for a fifth of a second, once, is a fair
 * price for one that never sticks inside a staircase, never needs the world
 * read off the main thread, and costs nothing to run.
 */
@ApiStatus.Internal
public final class RagdollBuilder {

    private RagdollBuilder() {
    }

    /**
     * Shows one body.
     *
     * @param owner   the plugin the displays belong to
     * @param model   whose body, how finely cut and what it carries
     * @param motion  what happens to it
     * @param at      where they died, standing on the floor
     * @param viewers who sees it
     * @return the displays it put on screen, in the order they were made
     */
    public static List<DisplayHandle> show(String owner, RagdollModel model, RagdollMotion motion,
                                           Location at, List<Player> viewers) {
        List<DisplayHandle> shown = new ArrayList<>();
        if (viewers.isEmpty()) {
            return shown;
        }
        // The body is built facing the way the location does, so a head still
        // looks the way the player was looking when they died.
        Rotation facing = Rotation.around(Rotation.Axis.Y, -Math.toRadians(at.getYaw()));
        int detail = model.detailCells();
        EnumSet<RagdollPieces.Prop> props = EnumSet.noneOf(RagdollPieces.Prop.class);
        if (model.mainHand() != null) {
            props.add(RagdollPieces.Prop.MAIN_HAND);
        }
        if (model.offHand() != null) {
            props.add(RagdollPieces.Prop.OFF_HAND);
        }
        if (model.hat() != null) {
            props.add(RagdollPieces.Prop.HAT);
        }
        for (RagdollPieces.Piece piece : RagdollPieces.solve(motion, detail, model.scaleFactor(),
                facing, ThreadLocalRandom.current(), props)) {
            DisplayModel drawn = drawn(model, piece, detail);
            DisplayHandle handle = DisplayRuntime.show(owner, drawn,
                    DisplayMotion.of(piece.poses(), motion.lifeMillis()), at, viewers);
            if (handle != null) {
                shown.add(handle);
            }
        }
        return shown;
    }

    /** What one piece is drawn with: a face, a carried item, or a block the colour of its skin. */
    private static DisplayModel drawn(RagdollModel model, RagdollPieces.Piece piece, int detail) {
        if (piece.prop() != null) {
            ItemStack item = switch (piece.prop()) {
                case MAIN_HAND -> model.mainHand();
                case OFF_HAND -> model.offHand();
                case HAT -> model.hat();
            };
            return DisplayModel.item(item == null ? new ItemStack(Material.AIR) : item)
                    .glow(model.glowArgb())
                    .light(model.brightness());
        }
        if (piece.part() == RagdollPart.HEAD) {
            ItemStack head = model.head();
            return DisplayModel.item(head == null ? new ItemStack(Material.PLAYER_HEAD) : head)
                    .glow(model.glowArgb())
                    .light(model.brightness());
        }
        return DisplayModel
                .block(BlockPalette.nearest(model.skin().colour(
                        piece.part(), piece.cellX(), piece.cellY(),
                        piece.part().columns(detail), piece.part().rows(detail))))
                .glow(model.glowArgb())
                .light(model.brightness());
    }
}
