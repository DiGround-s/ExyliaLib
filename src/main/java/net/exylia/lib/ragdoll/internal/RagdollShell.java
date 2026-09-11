package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollSkin;
import org.bukkit.Material;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A body in blocks wearing its skin's design on every face: detail five.
 *
 * <h2>A core and plates</h2>
 * A grid of cells can only get finer by getting more expensive, and at two
 * skin pixels a cell a body is already seventy-three displays showing one face
 * of its skin. A shell spends its displays where the design is instead. Each
 * part is one core block in the colour most of its surface is, and every run
 * of pixels in another block is laid over it as a thin plate &mdash; a stripe
 * across a shirt is one plate, not eight cells. All six faces are read, so a
 * logo on a back and the lace of a shoe come apart with the body too.
 *
 * <h2>Within budget</h2>
 * A busy skin can ask for more plates than an effect is allowed displays.
 * The largest plates carry the design and the smallest carry the noise, so
 * the smallest are dropped first and their pixels simply show the core.
 *
 * <p>Pure: a skin in, blocks with a place and a size out, in skin pixels in the
 * part's own frame &mdash; up, the body facing forward along +Z, its right
 * towards -X &mdash; so every plate can be asserted without a server.
 */
@ApiStatus.Internal
public final class RagdollShell {

    /** The detail that asks for a shell rather than a grid of cells. */
    public static final int DETAIL = 5;

    /**
     * How many pieces a whole shell may use.
     *
     * <p>displays.yml allows 128 displays per effect by default; the head,
     * whatever the body carries, and its strings or chains take the rest.
     */
    static final int BUDGET = 120;

    /**
     * How thick a plate is, in skin pixels.
     *
     * <p>A calibration knob. The core is inset by exactly this much, so a plate
     * sits flush with the part's surface: thin enough that nobody sees the
     * step where a plate meets bare core, thick enough that a plate seen edge
     * on is still a line and not a flicker.
     */
    static final float THICKNESS = 0.2f;

    /**
     * How many blocks one part may be drawn in, its core included.
     *
     * <p>A calibration knob: more keeps finer shading and costs plates, fewer
     * merges more and flattens the design. Measured on the nine default skins
     * with dither taken out: three blocks is 80 to 159 pieces, four is 123 to
     * 173 &mdash; three is what keeps most bodies whole inside the budget.
     */
    static final int SHADES = 3;

    private RagdollShell() {
    }

    /**
     * One block of a shell.
     *
     * @param part   the part it belongs to
     * @param centre where its middle sits inside the part before the part is
     *               turned, in skin pixels
     * @param size   how big it is on each axis, in skin pixels
     * @param block  what it is drawn in
     */
    public record Piece(RagdollPart part, float[] centre, float[] size, Material block) {
    }

    /**
     * A shell as pieces the solver places.
     *
     * @param shell the cores and plates
     * @return the same blocks, in the same order
     */
    public static List<RagdollPieces.Placed> placed(List<Piece> shell) {
        List<RagdollPieces.Placed> placed = new ArrayList<>(shell.size());
        for (Piece piece : shell) {
            placed.add(new RagdollPieces.Placed(piece.part(), piece.centre(), piece.size(), piece.block(), null));
        }
        return placed;
    }

    /**
     * Every block of a body but its head, within the budget.
     *
     * @param skin the skin
     * @return the cores and the plates, part by part
     */
    public static List<Piece> build(RagdollSkin skin) {
        return build(skin, BUDGET);
    }

    static List<Piece> build(RagdollSkin skin, int budget) {
        return build(skin, budget, SHADES);
    }

    static List<Piece> build(RagdollSkin skin, int budget, int shades) {
        List<Piece> pieces = new ArrayList<>();
        List<Piece> plates = new ArrayList<>();
        carveAll(skin, shades, false, pieces, plates);
        if (pieces.size() + plates.size() > budget) {
            // Over budget, the dither goes before any plate does: a lone pixel
            // taking its neighbours' block lets whole runs merge again, which
            // costs far less of the design than dropping plates outright.
            pieces.clear();
            plates.clear();
            carveAll(skin, shades, true, pieces, plates);
        }
        int room = Math.max(0, budget - pieces.size());
        if (plates.size() > room) {
            List<Piece> largest = new ArrayList<>(plates);
            // Stable, so equal plates keep the order they were found in.
            largest.sort(Comparator.comparingDouble(RagdollShell::volume).reversed());
            Set<Piece> kept = Collections.newSetFromMap(new IdentityHashMap<>());
            kept.addAll(largest.subList(0, room));
            plates.removeIf(plate -> !kept.contains(plate));
        }
        pieces.addAll(plates);
        return pieces;
    }

    /** Every part but the head, cores and plates, before any budget. */
    static void carveAll(RagdollSkin skin, int shades, boolean smooth, List<Piece> cores, List<Piece> plates) {
        for (RagdollPart part : RagdollPart.values()) {
            if (part != RagdollPart.HEAD) {
                carve(part, skin.net(part), shades, smooth, cores, plates);
            }
        }
    }

    /** One part: its core, and a plate for every run of another block on each face. */
    private static void carve(RagdollPart part, int[] net, int shades, boolean smooth,
                              List<Piece> cores, List<Piece> plates) {
        int w = Math.round(part.blockWidth() / RagdollPart.PIXEL);
        int h = Math.round(part.blockHeight() / RagdollPart.PIXEL);
        int d = Math.round(part.blockDepth() / RagdollPart.PIXEL);
        SkinCubes.Box shape = new SkinCubes.Box(0, 0, w, h, d);
        int netWidth = 2 * (d + w);
        int[] pixels = net == null ? new int[0] : net;
        // The net holds the six faces and nothing else solid, so the block
        // most of it is, is the block most of the part's surface is.
        Material core = BlockPalette.dominant(pixels);
        Set<Material> kept = shades(pixels, core, shades);
        cores.add(new Piece(part, new float[]{0f, 0f, 0f},
                new float[]{w - 2 * THICKNESS, h - 2 * THICKNESS, d - 2 * THICKNESS}, core));
        for (SkinCubes.Face face : SkinCubes.Face.values()) {
            int rows = shape.height(face);
            int columns = shape.width(face);
            Material[][] grid = new Material[rows][columns];
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    int index = (shape.y(face) + row) * netWidth + shape.x(face) + column;
                    int argb = index < pixels.length ? pixels[index] : 0;
                    // A clear pixel shows the core rather than a hole.
                    Material block = (argb >>> 24) < 128 ? core
                            : BlockPalette.nearestAmong(BlockPalette.material(argb), kept);
                    grid[row][column] = block == core ? null : block;
                }
            }
            if (smooth) {
                grid = despeckle(grid);
            }
            boolean[][] taken = new boolean[rows][columns];
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    Material block = grid[row][column];
                    if (block == null || taken[row][column]) {
                        continue;
                    }
                    int right = column + 1;
                    while (right < columns && !taken[row][right] && grid[row][right] == block) {
                        right++;
                    }
                    int bottom = row + 1;
                    while (bottom < rows && runs(grid, taken, bottom, column, right, block)) {
                        bottom++;
                    }
                    for (int r = row; r < bottom; r++) {
                        for (int c = column; c < right; c++) {
                            taken[r][c] = true;
                        }
                    }
                    plates.add(plate(part, face, w, h, d, column, right, row, bottom, block));
                }
            }
        }
    }

    /**
     * The few blocks a part is drawn in: its core and the next most common.
     *
     * <p>A skin is shaded. Every sleeve is three near colours dithered
     * together, the palette matches each of them to a different block, and a
     * run of pixels that is one colour to the eye is a dozen plates that never
     * merge &mdash; every default skin asked for twice the budget. Snapping each
     * part to its few commonest blocks keeps the design and drops the dither,
     * so a sleeve is one plate again.
     */
    static Set<Material> shades(int[] pixels, Material core, int shades) {
        // An EnumMap so equal counts are always ranked in the same order.
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (int pixel : pixels) {
            if ((pixel >>> 24) >= 128) {
                counts.merge(BlockPalette.material(pixel), 1, Integer::sum);
            }
        }
        Set<Material> kept = new LinkedHashSet<>();
        kept.add(core);
        counts.entrySet().stream()
                .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .filter(material -> material != core)
                .limit(shades - 1L)
                .forEach(kept::add);
        return kept;
    }

    /**
     * One face with every lone pixel given the block most of its neighbours are.
     *
     * <p>A pixel that matches none of the four around it is dither, not design,
     * and it is what keeps a run from merging into one plate. Read from the
     * face as it was, so a pixel changed here never decides its neighbour.
     *
     * @param grid the face, {@code null} where it shows the core
     * @return a new grid
     */
    static Material[][] despeckle(Material[][] grid) {
        int rows = grid.length;
        Material[][] smoothed = new Material[rows][];
        for (int row = 0; row < rows; row++) {
            int columns = grid[row].length;
            smoothed[row] = grid[row].clone();
            for (int column = 0; column < columns; column++) {
                Material self = grid[row][column];
                Material[] around = new Material[4];
                int count = 0;
                if (row > 0) {
                    around[count++] = grid[row - 1][column];
                }
                if (row < rows - 1) {
                    around[count++] = grid[row + 1][column];
                }
                if (column > 0) {
                    around[count++] = grid[row][column - 1];
                }
                if (column < columns - 1) {
                    around[count++] = grid[row][column + 1];
                }
                boolean alone = count > 0;
                for (int index = 0; index < count && alone; index++) {
                    alone = around[index] != self;
                }
                if (!alone) {
                    continue;
                }
                Material most = self;
                int mostCount = 1;
                for (int index = 0; index < count; index++) {
                    int same = 0;
                    for (int other = 0; other < count; other++) {
                        if (around[other] == around[index]) {
                            same++;
                        }
                    }
                    if (same > mostCount) {
                        most = around[index];
                        mostCount = same;
                    }
                }
                smoothed[row][column] = most;
            }
        }
        return smoothed;
    }

    /** Whether a whole span of a row is still free and all one block. */
    private static boolean runs(Material[][] grid, boolean[][] taken, int row, int from, int to, Material block) {
        for (int column = from; column < to; column++) {
            if (taken[row][column] || grid[row][column] != block) {
                return false;
            }
        }
        return true;
    }

    /**
     * A rectangle of one face as a plate: flush with the surface, as thick as
     * the core is inset.
     *
     * <p>Faces run the way the skin unfolds them: a front's first column is the
     * wearer's right, a back's is their left, the right side runs from the
     * back to the front and the left from the front to the back, and a top's
     * last row is the edge it shares with the front.
     */
    static Piece plate(RagdollPart part, SkinCubes.Face face, int w, int h, int d,
                       int left, int right, int top, int bottom, Material block) {
        float across = (left + right) / 2f;
        float down = (top + bottom) / 2f;
        float wide = right - left;
        float tall = bottom - top;
        float t = THICKNESS;
        return switch (face) {
            case FRONT -> new Piece(part, new float[]{-w / 2f + across, h / 2f - down, d / 2f - t / 2},
                    new float[]{wide, tall, t}, block);
            case BACK -> new Piece(part, new float[]{w / 2f - across, h / 2f - down, -d / 2f + t / 2},
                    new float[]{wide, tall, t}, block);
            case RIGHT -> new Piece(part, new float[]{-w / 2f + t / 2, h / 2f - down, -d / 2f + across},
                    new float[]{t, tall, wide}, block);
            case LEFT -> new Piece(part, new float[]{w / 2f - t / 2, h / 2f - down, d / 2f - across},
                    new float[]{t, tall, wide}, block);
            case TOP -> new Piece(part, new float[]{-w / 2f + across, h / 2f - t / 2, -d / 2f + down},
                    new float[]{wide, t, tall}, block);
            // Which way round a bottom is unfolded is a best guess nobody has
            // checked in game: it is the face a body shows least.
            case BOTTOM -> new Piece(part, new float[]{-w / 2f + across, -h / 2f + t / 2, d / 2f - down},
                    new float[]{wide, t, tall}, block);
        };
    }

    private static double volume(Piece piece) {
        return (double) piece.size()[0] * piece.size()[1] * piece.size()[2];
    }
}
