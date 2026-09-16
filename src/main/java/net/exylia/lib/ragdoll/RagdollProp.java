package net.exylia.lib.ragdoll;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A thing made of blocks, tied to a joint, that moves with the body.
 *
 * <pre>{@code
 * tophat:
 *   joint: head
 *   at: 0, 0.06, 0
 *   blocks:
 *     - 'ring BLACK_CONCRETE r:0.40 n:14 s:0.10'
 *     - 'ring BLACK_CONCRETE r:0.22 n:10 s:0.10 y:0.10'
 *     - 'ring RED_CONCRETE   r:0.23 n:10 s:0.10 y:0.30'
 *     - 'disc BLACK_CONCRETE r:0.22 n:10 s:0.10 y:0.60'
 * }</pre>
 *
 * <h2>What this is for</h2>
 * Everything a body could hold, wear or carry, without any of it being written
 * into this library. A hat, a crown, a sword, a cake, a pair of wings, a thing
 * nobody has thought of — all of them are a handful of blocks and a joint, and
 * none of them are a special case anywhere in the code.
 *
 * <p>Before this, a body could carry exactly three things and each of them had
 * to be an item: a flat sprite in a hand, or a whole block the size of a head.
 * A shape made of displays could be drawn near a body but never attached to it,
 * because a display is placed against the world and a hand is not in the world,
 * it is somewhere in the middle of an animation.
 *
 * <h2>Solved once, like everything else</h2>
 * Every block of a prop is carried by its joint exactly the way a cell of an arm
 * is carried by that arm: its offset is turned by whatever the joint has turned
 * to and added to wherever the joint has gone, for every frame, before the first
 * packet is sent. A prop costs what its blocks cost and not one packet more, and
 * it cannot come loose, because nothing is following anything at runtime.
 *
 * <h2>The three words</h2>
 * A block line is one of {@code at}, {@code ring} or {@code disc}, a material,
 * and then named numbers. {@code at} is one block. {@code ring} is {@code n} of
 * them in a circle of radius {@code r}. {@code disc} is a ring with its middle
 * filled in. All of them take {@code s} for how big each block is and
 * {@code x} {@code y} {@code z} for where the line sits, in the joint's own
 * axes: {@code y} is along the limb, {@code z} is the way the body faces.
 *
 * @param id     what a file calls it
 * @param joint  where it is tied
 * @param at     where it sits relative to the joint, in blocks
 * @param blocks every block in it, already laid out
 * @since 1.173.0
 */
public record RagdollProp(@NotNull String id, @NotNull RagdollJoint joint,
                          float @NotNull [] at, @NotNull List<Block> blocks) {

    /** How many blocks one prop may be made of, so a typo cannot flood a client. */
    public static final int MAX_BLOCKS = 256;

    public RagdollProp {
        blocks = List.copyOf(blocks);
    }

    /**
     * One block of a prop.
     *
     * @param material what it is drawn with
     * @param x        across the joint, towards the body's left
     * @param y        along the joint
     * @param z        the way the body faces
     * @param size     how big it is, in blocks
     */
    public record Block(@NotNull Material material, float x, float y, float z, float size) {
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /**
     * Reads a prop's block lines.
     *
     * <p>A line that cannot be read is reported and skipped, never thrown: one
     * mistyped ring must not cost a server every other prop it wrote.
     *
     * @param id       what to call it
     * @param joint    where it is tied
     * @param at       where it sits relative to that joint
     * @param lines    the block lines
     * @param problems where each thing that could not be read is described
     */
    public static @NotNull RagdollProp parse(@NotNull String id, @NotNull RagdollJoint joint,
                                             float @NotNull [] at, @NotNull List<String> lines,
                                             @NotNull Consumer<String> problems) {
        List<Block> blocks = new ArrayList<>();
        int number = 0;
        for (String line : lines) {
            number++;
            String[] words = line.trim().split("\\s+");
            if (words.length < 2) {
                problems.accept("line " + number + " is not a shape and a material");
                continue;
            }
            Material material = Material.matchMaterial(words[1].trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                problems.accept("line " + number + ": there is no material called \""
                        + words[1] + "\"");
                continue;
            }

            double x = 0, y = 0, z = 0, radius = 0.3, size = 0.1;
            int count = 8;
            boolean bad = false;
            for (int index = 2; index < words.length; index++) {
                String word = words[index];
                int colon = word.indexOf(':');
                if (colon <= 0) {
                    problems.accept("line " + number + ": \"" + word + "\" is not name:value");
                    bad = true;
                    break;
                }
                String name = word.substring(0, colon).toLowerCase(Locale.ROOT);
                double value;
                try {
                    value = Double.parseDouble(word.substring(colon + 1));
                } catch (NumberFormatException malformed) {
                    problems.accept("line " + number + ": \"" + word + "\" is not a number");
                    bad = true;
                    break;
                }
                switch (name) {
                    case "x" -> x = value;
                    case "y" -> y = value;
                    case "z" -> z = value;
                    case "r", "radius" -> radius = value;
                    case "n", "count" -> count = (int) Math.round(value);
                    case "s", "size" -> size = value;
                    default -> {
                        problems.accept("line " + number + ": there is nothing called \""
                                + name + "\"");
                        bad = true;
                    }
                }
                if (bad) {
                    break;
                }
            }
            if (bad) {
                continue;
            }
            if (size <= 0) {
                problems.accept("line " + number + " draws blocks of no size");
                continue;
            }

            switch (words[0].toLowerCase(Locale.ROOT)) {
                case "at", "block", "one" ->
                        blocks.add(new Block(material, (float) x, (float) y, (float) z, (float) size));
                case "ring", "circle" -> ring(blocks, material, x, y, z, radius, count, size);
                case "disc", "filled" -> {
                    // Rings of falling radius until the middle is covered. Each
                    // one gets as many blocks as it needs to have no gaps in it,
                    // which is what a filled circle actually is.
                    for (double r = radius; r > 0.01; r -= size * 0.9) {
                        int many = Math.max(1, (int) Math.ceil(2 * Math.PI * r / (size * 0.9)));
                        ring(blocks, material, x, y, z, r, Math.min(many, count * 2), size);
                    }
                    blocks.add(new Block(material, (float) x, (float) y, (float) z, (float) size));
                }
                default -> problems.accept("line " + number + ": there is no shape called \""
                        + words[0] + "\"; write at, ring or disc");
            }

            if (blocks.size() > MAX_BLOCKS) {
                problems.accept("has more than " + MAX_BLOCKS
                        + " blocks in it; the rest were dropped");
                return new RagdollProp(id, joint, at, blocks.subList(0, MAX_BLOCKS));
            }
        }
        return new RagdollProp(id, joint, at, blocks);
    }

    private static void ring(List<Block> into, Material material,
                             double x, double y, double z, double radius, int count, double size) {
        int many = Math.max(1, count);
        for (int index = 0; index < many; index++) {
            double angle = 2 * Math.PI * index / many;
            into.add(new Block(material,
                    (float) (x + Math.cos(angle) * radius),
                    (float) y,
                    (float) (z + Math.sin(angle) * radius),
                    (float) size));
        }
    }
}
