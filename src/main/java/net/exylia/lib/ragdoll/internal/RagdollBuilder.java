package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayKeyframe;
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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Works out where every piece of a body goes, and hands the answer to the
 * display module.
 *
 * <h2>Nothing is moved, everything is told</h2>
 * The whole flight of every piece is solved here, once, the moment the body
 * bursts: a couple of dozen poses per piece, each with the time it is reached.
 * The client is then handed those poses and draws every frame in between at its
 * own frame rate.
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

    /** How long the pieces take to shrink away at the end. */
    private static final long FADE_MS = 260L;

    /**
     * How far a head item sits below the entity it is drawn on.
     *
     * <p>The vanilla head model fills the lower half of an item's space, so an
     * item display of one draws a quarter of a block low. A calibration knob:
     * a resource pack with its own head model wants a different number.
     */
    private static final float HEAD_LIFT = 0.25f;

    private RagdollBuilder() {
    }

    /**
     * Shows one body coming apart.
     *
     * @param owner   the plugin the displays belong to
     * @param model   whose body, and how finely cut
     * @param burst   how it comes apart
     * @param at      where they died, standing on the floor
     * @param viewers who sees it
     * @return the displays it put on screen, in the order they were made
     */
    public static List<DisplayHandle> show(String owner, RagdollModel model, RagdollMotion burst,
                                           Location at, List<Player> viewers) {
        List<DisplayHandle> shown = new ArrayList<>();
        if (viewers.isEmpty()) {
            return shown;
        }
        double scale = model.scaleFactor();
        // The body is built facing the way the location does, so a head still
        // looks the way the player was looking when they died.
        Rotation facing = Rotation.around(Rotation.Axis.Y, -Math.toRadians(at.getYaw()));
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (RagdollPart part : RagdollPart.values()) {
            RagdollFlight.Flight flight = RagdollFlight.solve(part, burst, scale, facing, random);
            if (part == RagdollPart.HEAD) {
                show(owner, headModel(model), flight, new float[]{0f, HEAD_LIFT * (float) scale, 0f},
                        new float[]{(float) scale, (float) scale, (float) scale},
                        burst, at, viewers, shown);
                continue;
            }
            cells(owner, model, part, flight, burst, scale, facing, at, viewers, shown);
        }
        return shown;
    }

    /** Draws one part as a grid of blocks in the colours its skin actually is. */
    private static void cells(String owner, RagdollModel model, RagdollPart part,
                              RagdollFlight.Flight flight,
                              RagdollMotion burst, double scale, Rotation facing, Location at,
                              List<Player> viewers, List<DisplayHandle> shown) {
        int detail = model.detailCells();
        float width = part.blockWidth() * (float) scale;
        float height = part.blockHeight() * (float) scale;
        float depth = part.blockDepth() * (float) scale;
        float[] size = {width / detail, height / detail, depth};

        // Every piece but the head, counted once across the whole body, so a
        // word can be shared out among them.
        int pieces = (RagdollPart.values().length - 1) * detail * detail;
        boolean spelling = burst.pose() == net.exylia.lib.ragdoll.RagdollPose.SIGN;

        for (int cellY = 0; cellY < detail; cellY++) {
            for (int cellX = 0; cellX < detail; cellX++) {
                DisplayModel cell = DisplayModel
                        .block(BlockPalette.nearest(model.skin().colour(part, cellX, cellY, detail)))
                        .glow(model.glowArgb())
                        .light(model.brightness());
                // Where this cell sits inside its own part, before the part is
                // turned. The grid runs left to right and top to bottom, as the
                // skin does.
                float[] local = {
                        ((cellX + 0.5f) / detail - 0.5f) * width,
                        (0.5f - (cellY + 0.5f) / detail) * height,
                        0f};
                if (spelling) {
                    int index = (part.ordinal() - 1) * detail * detail + cellY * detail + cellX;
                    RagdollSign.Placement to = RagdollSign.place(
                            burst.sign(), burst.letters(), index, pieces);
                    if (to == null) {
                        continue;
                    }
                    // Placed by the word rather than by the body, so its offset
                    // inside the part it came from is no longer of any use.
                    double[] from = {
                            flight.x()[0] + local[0],
                            flight.y()[0] + local[1],
                            flight.z()[0] + local[2]};
                    show(owner, cell,
                            RagdollFlight.signCell(burst, scale, facing, from, to, size),
                            new float[]{0f, 0f, 0f}, size, burst, at, viewers, shown);
                    continue;
                }
                show(owner, cell, flight, local, size, burst, at, viewers, shown);
            }
        }
    }

    /** Turns one piece's flight into poses and puts it on screen. */
    private static void show(String owner, DisplayModel model, RagdollFlight.Flight flight,
                             float[] local, float[] size, RagdollMotion burst, Location at,
                             List<Player> viewers, List<DisplayHandle> shown) {
        List<DisplayKeyframe> poses = new ArrayList<>(flight.times().length);
        for (int index = 0; index < flight.times().length; index++) {
            Rotation rotation = flight.rotations()[index];
            double[] grown = flight.scales()[index];
            // The cell is carried by the part: its own offset grows with the
            // part, is turned by whatever the part has turned to, and is then
            // added to where the part is. Growing it afterwards would leave a
            // swollen head as four cells drifting apart from each other.
            float[] carried = rotation.apply(new float[]{
                    (float) (local[0] * grown[0]),
                    (float) (local[1] * grown[1]),
                    (float) (local[2] * grown[2])});
            float shrink = shrink(flight.times()[index], burst);
            poses.add(new DisplayKeyframe(flight.times()[index],
                    (float) flight.x()[index] + carried[0],
                    (float) flight.y()[index] + carried[1],
                    (float) flight.z()[index] + carried[2],
                    rotation,
                    (float) (size[0] * shrink * grown[0]),
                    (float) (size[1] * shrink * grown[1]),
                    (float) (size[2] * shrink * grown[2])));
        }
        DisplayHandle handle = DisplayRuntime.show(owner, model,
                DisplayMotion.of(poses, burst.lifeMillis()), at, viewers);
        if (handle != null) {
            shown.add(handle);
        }
    }

    /** How much of its size a piece still has, so it shrinks away at the end. */
    private static float shrink(long atMillis, RagdollMotion burst) {
        if (!burst.fade()) {
            return 1f;
        }
        long remaining = burst.lifeMillis() - atMillis;
        return remaining >= FADE_MS ? 1f : Math.max(0.02f, (float) remaining / FADE_MS);
    }

    /** The head, drawn with the real face rather than matched to a block. */
    private static DisplayModel headModel(RagdollModel model) {
        ItemStack head = model.head();
        return DisplayModel.item(head == null ? new ItemStack(Material.PLAYER_HEAD) : head)
                .glow(model.glowArgb())
                .light(model.brightness());
    }
}
