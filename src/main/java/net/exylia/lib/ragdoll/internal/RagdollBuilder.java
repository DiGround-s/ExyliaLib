package net.exylia.lib.ragdoll.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.display.DisplayHandle;
import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.display.internal.DisplayRuntime;
import net.exylia.lib.ragdoll.RagdollFinish;
import net.exylia.lib.ragdoll.RagdollMotion;
import net.exylia.lib.ragdoll.RagdollModel;
import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollPose;
import net.exylia.lib.ragdoll.RagdollSkin;
import net.exylia.lib.skull.internal.HeadFactory;
import net.exylia.lib.skull.internal.Textures;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
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

    /** The chain block, looked up by name: the copper update renamed it IRON_CHAIN. */
    private static final Material CHAIN = java.util.Objects.requireNonNullElse(
            Material.matchMaterial("IRON_CHAIN"),
            java.util.Objects.requireNonNullElse(Material.matchMaterial("CHAIN"), Material.IRON_BARS));

    /**
     * One head per texture, built once.
     *
     * <p>A head is cheap and building ten of them on the main thread at every
     * death is not, but a server that keeps every head it ever built keeps one
     * for every sleeve of every player who ever died there.
     */
    private static final Cache<String, ItemStack> HEADS = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

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
        // Whoever shows bodies keeps the textures they are drawn with.
        RagdollTextures.register(owner);
        // The body is built facing the way the location does, so a head still
        // looks the way the player was looking when they died.
        Rotation facing = Rotation.around(Rotation.Axis.Y, -Math.toRadians(at.getYaw()));
        // A word is laid out in cells, and neither heads nor thin plates can be.
        boolean spelling = motion.pose() == RagdollPose.SIGN
                || motion.pose() == RagdollPose.ANIMATE && motion.finish() == RagdollFinish.SPELL;
        // Detail five is a shell rather than a finer grid, so the grid it falls
        // back to when the body spells a word is the finest there is.
        int detail = Math.min(model.detailCells(), RagdollShell.DETAIL - 1);
        List<RagdollPieces.Placed> placed = spelling ? null : worn(model.skin(), SkinCache.quality());
        if (placed == null && !spelling && model.detailCells() >= RagdollShell.DETAIL) {
            placed = RagdollShell.placed(RagdollShell.build(model.skin()));
        }
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
                facing, ThreadLocalRandom.current(), props, placed)) {
            DisplayModel drawn = drawn(model, piece, detail);
            DisplayHandle handle = DisplayRuntime.show(owner, drawn,
                    DisplayMotion.of(piece.poses(), motion.lifeMillis()), at, viewers);
            if (handle != null) {
                shown.add(handle);
            }
        }
        return shown;
    }

    /**
     * A body in its real skin, region by region, or {@code null} when it has
     * nothing real to wear yet.
     *
     * <p>Drawn by parts: a region whose texture has arrived is a head, a region
     * that is one flat block anyway is that block, and a region still waiting
     * is a block of its commonest colour at its own size. Worn once any region
     * is real, or when every region is plain; until then the body is cut the
     * way its detail asks, which reads better than a handful of large blocks.
     *
     * @param skin    whose skin
     * @param quality how finely it is cut
     * @return the pieces to place, or {@code null} for the body in blocks
     */
    static @Nullable List<RagdollPieces.Placed> worn(RagdollSkin skin, SkinCubes.Quality quality) {
        List<SkinCubes.Cube> cubes = skin.cubes(quality);
        if (cubes == null) {
            return null;
        }
        boolean real = false;
        boolean plain = true;
        List<RagdollPieces.Placed> placed = new ArrayList<>(cubes.size());
        for (SkinCubes.Cube cube : cubes) {
            String texture = cube.plain() == null ? RagdollTextures.known(cube.hash()) : null;
            plain &= cube.plain() != null;
            real |= texture != null;
            Material block = texture != null ? null : cube.plain() != null ? cube.plain() : cube.dominant();
            placed.add(new RagdollPieces.Placed(cube.part(), cube.region().centre(), cube.region().size(),
                    block, texture));
        }
        return real || plain ? placed : null;
    }

    /** What one piece is drawn with: a face, a carried item, a head of real skin, or a block. */
    private static DisplayModel drawn(RagdollModel model, RagdollPieces.Piece piece, int detail) {
        if (piece.prop() != null) {
            ItemStack item = switch (piece.prop()) {
                case MAIN_HAND -> model.mainHand();
                case OFF_HAND -> model.offHand();
                case HAT -> model.hat();
                case STRING_RIGHT, STRING_LEFT, STRING_HEAD, CHAIN_RIGHT, CHAIN_LEFT -> null;
            };
            if (item == null && piece.prop().name().startsWith("STRING")) {
                return DisplayModel.block(Material.WHITE_WOOL.createBlockData()).light(15);
            }
            if (item == null && piece.prop().name().startsWith("CHAIN")) {
                return DisplayModel.block(CHAIN.createBlockData()).light(15);
            }
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
        if (piece.texture() != null) {
            ItemStack head = HEADS.get(piece.texture(), id -> HeadFactory.create(Textures.fromUrl(id)));
            return DisplayModel.item(head)
                    .glow(model.glowArgb())
                    .light(model.brightness());
        }
        if (piece.block() != null) {
            return DisplayModel.block(BlockPalette.block(piece.block()))
                    .glow(model.glowArgb())
                    .light(model.brightness());
        }
        return DisplayModel
                .block(BlockPalette.block(BlockPalette.dominant(model.skin().cell(
                        piece.part(), piece.cellX(), piece.cellY(),
                        piece.part().columns(detail), piece.part().rows(detail)))))
                .glow(model.glowArgb())
                .light(model.brightness());
    }
}
