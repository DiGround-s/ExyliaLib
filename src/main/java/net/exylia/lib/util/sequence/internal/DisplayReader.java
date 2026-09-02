package net.exylia.lib.util.sequence.internal;

import net.exylia.lib.display.DisplayModel;
import net.exylia.lib.display.DisplayMotion;
import net.exylia.lib.display.Rotation;
import net.exylia.lib.text.Text;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Turns one {@code as:} line into a display paint, once, when the file is read.
 *
 * <p>Everything a display effect needs is a number or a name in configuration,
 * and all of it is resolved here: the item is an {@code ItemStack}, the turns
 * are quaternions, the seconds are milliseconds. Playing the line afterwards
 * spawns entities and sends packets, and parses nothing.
 *
 * <h2>The parameters are choreography, not physics</h2>
 * {@code from} and {@code to} are where it starts and stops; {@code gravity} is
 * added on top of that rather than replacing it. A server owner writing "throw
 * it four blocks up and let it fall" writes two numbers and gets what they
 * pictured, instead of solving for a launch velocity.
 */
final class DisplayReader {

    /** Everything a display line may say, for naming a typo at load. */
    static final String[] PARAMETERS = {
            "as", "size", "size_to", "life", "from", "to", "rise", "spin", "axis",
            "tilt", "roll", "turn", "face_out", "gravity", "glow", "light", "model",
            "billboard", "hold", "pull"
    };

    private DisplayReader() {
    }

    /** Whether a line asks for displays rather than particles. */
    static boolean wanted(@NotNull Args args, @NotNull String token) {
        return args.has("as") || token.equals("DISPLAY");
    }

    /**
     * Builds the paint one line describes.
     *
     * @param owner    the plugin the displays will belong to
     * @param args     the line's arguments
     * @param token    the token, so {@code [DISPLAY]} can default to an item
     * @param problems where a bad value is reported
     * @return the paint, or {@code null} when the line names nothing drawable
     */
    static @Nullable Paint read(@NotNull String owner, @NotNull Args args, @NotNull String token,
                                @NotNull Args.Problems problems) {
        String kind = args.text("as", token.equals("DISPLAY") ? "item" : "").toLowerCase(Locale.ROOT);
        DisplayPaint.Face face = DisplayPaint.Face.FIXED;
        DisplayModel model;

        switch (kind) {
            case "item" -> {
                Material material = material(args.head(), problems);
                if (material == null) {
                    return null;
                }
                model = DisplayModel.item(withModelData(new ItemStack(material), args, problems));
            }
            case "block" -> {
                BlockData block = block(args.head(), problems);
                if (block == null) {
                    return null;
                }
                model = DisplayModel.block(block);
            }
            case "head" -> {
                String source = args.head().trim();
                face = switch (source.toLowerCase(Locale.ROOT)) {
                    case "{killer}", "killer" -> DisplayPaint.Face.KILLER;
                    case "{victim}", "victim" -> DisplayPaint.Face.VICTIM;
                    default -> DisplayPaint.Face.FIXED;
                };
                ItemStack head = face == DisplayPaint.Face.FIXED && !source.isEmpty()
                        ? Heads.textured(source)
                        : Heads.blank();
                model = DisplayModel.item(withModelData(head, args, problems));
            }
            case "text" -> model = DisplayModel.text(Text.of(args.head()).build());
            default -> {
                problems.found("as", "\"" + kind + "\" is not item, block, head or text");
                return null;
            }
        }

        Color glow = args.colour("glow", null, problems);
        if (glow != null) {
            model = model.glow(glow.asRGB());
        }
        if (args.has("light")) {
            model = model.light(args.count("light", 15, problems));
        }
        if (args.has("billboard")) {
            model = model.billboard(args.text("billboard", "FIXED"));
        }
        if (args.has("hold")) {
            model = model.held(args.count("hold", 0, problems));
        }

        return new DisplayPaint(owner, model, motion(args, problems), face,
                args.flag("face_out", false),
                Math.toRadians(args.number("turn", 0.0, problems)),
                args.number("pull", 0.0, problems));
    }

    /**
     * The movement the line describes.
     *
     * <p>{@code rise:} is {@code to:} for the common case, because "it goes up
     * two blocks" should not require writing a vector, and a file full of
     * {@code to:0,2,0} is a file nobody skims.
     */
    private static DisplayMotion motion(Args args, Args.Problems problems) {
        double size = args.number("size", 1.0, problems);
        double[] from = triple(args, "from", problems);
        double[] to = triple(args, "to", problems);
        if (!args.has("to") && args.has("rise")) {
            to[1] = args.number("rise", 0.0, problems);
        }
        return DisplayMotion.builder()
                .life((long) (args.number("life", 1.0, problems) * 1000))
                .from(from[0], from[1], from[2])
                .to(to[0], to[1], to[2])
                .scale(size, args.number("size_to", size, problems))
                .rotation(Rotation.around(Rotation.Axis.X,
                                Math.toRadians(args.number("tilt", 0.0, problems)))
                        .then(Rotation.around(Rotation.Axis.Z,
                                Math.toRadians(args.number("roll", 0.0, problems)))))
                .spin(Rotation.Axis.of(args.text("axis", "y")),
                        args.number("spin", 0.0, problems))
                .gravity(args.number("gravity", 0.0, problems))
                .build();
    }

    /** An {@code x,y,z} parameter, or zeroes. */
    private static double[] triple(Args args, String key, Args.Problems problems) {
        double[] out = new double[3];
        if (!args.has(key)) {
            return out;
        }
        String[] parts = args.text(key, "").split(",");
        if (parts.length < 3) {
            problems.found(key, "needs three numbers, as in " + key + ":0,2,0");
            return out;
        }
        for (int index = 0; index < 3; index++) {
            try {
                out[index] = Double.parseDouble(parts[index].trim());
            } catch (NumberFormatException malformed) {
                problems.found(key, "\"" + parts[index].trim() + "\" is not a number");
            }
        }
        return out;
    }

    /**
     * Stamps a resource pack model onto the item.
     *
     * <p>The one parameter that decides whether an effect looks like Minecraft
     * or like something built for this server: a custom model turns an item
     * display into a rune, a shockwave plate or a shard of a broken weapon.
     */
    private static ItemStack withModelData(ItemStack item, Args args, Args.Problems problems) {
        if (!args.has("model")) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setCustomModelData(args.count("model", 0, problems));
        item.setItemMeta(meta);
        return item;
    }

    private static @Nullable Material material(String name, Args.Problems problems) {
        if (name.isBlank()) {
            problems.found("as", "needs an item, as in [DISPLAY] NETHERITE_SWORD");
            return null;
        }
        try {
            return Material.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            problems.found("as", "there is no item called \"" + name.trim() + "\"");
            return null;
        }
    }

    /**
     * A block, by name or as a full block state.
     *
     * <p>The state form is worth accepting: a wall of {@code oak_stairs} all
     * facing the same way is a flat plane, and the difference between that and
     * a shattered floor is one bracket in the file.
     */
    private static @Nullable BlockData block(String name, Args.Problems problems) {
        String value = name.trim();
        if (value.isBlank()) {
            problems.found("as", "needs a block, as in [DISPLAY] CRYING_OBSIDIAN;as:block");
            return null;
        }
        if (value.indexOf('[') >= 0 || value.indexOf(':') >= 0) {
            try {
                return Bukkit.createBlockData(value.toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException malformed) {
                problems.found("as", "\"" + value + "\" is not a block state");
                return null;
            }
        }
        try {
            Material material = Material.valueOf(value.toUpperCase(Locale.ROOT));
            if (!material.isBlock()) {
                problems.found("as", "\"" + value + "\" is not a block");
                return null;
            }
            return material.createBlockData();
        } catch (IllegalArgumentException unknown) {
            problems.found("as", "there is no block called \"" + value + "\"");
            return null;
        }
    }
}
