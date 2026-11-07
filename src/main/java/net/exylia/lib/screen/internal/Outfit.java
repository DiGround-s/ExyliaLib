package net.exylia.lib.screen.internal;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a body on a screen wears: the armour layer textures for each slot (base, leather overlay, trim, in drawing
 * order) and the sprite of the item in its hand. Read from the client jar's textures, so it is built off the server
 * threads ({@link #resolve}).
 *
 * <p>Written as one word per item, space-separated: {@code <material>[#rrggbb][@pattern:material]}, the colour for
 * leather and the trim for any armour ({@code diamond_helmet leather_chestplate#a06540 iron_leggings@coast:gold
 * diamond_sword}). Armour finds its slot from its name; the first other item is held.
 */
public record Outfit(List<BufferedImage> head, List<BufferedImage> chest, List<BufferedImage> legs, List<BufferedImage> feet,
              @Nullable Pictures.Picture hand) {

    static final Outfit NONE = new Outfit(List.of(), List.of(), List.of(), List.of(), null);

    /** One item of an outfit as written. */
    record Piece(String material, int tint, @Nullable String trimPattern, @Nullable String trimMaterial) {
        String token() {
            StringBuilder out = new StringBuilder(material);
            if (tint >= 0) out.append('#').append(String.format(Locale.ROOT, "%06x", tint));
            if (trimPattern != null && trimMaterial != null) out.append('@').append(trimPattern).append(':').append(trimMaterial);
            return out.toString();
        }
    }

    /** The words for these items: what {@code wearing=} takes. Air and {@code null} are left out. */
    @SuppressWarnings({"deprecation", "removal"}) // Keyed#getKey on trims: the one spelling 1.21.1 to 1.21.4 share.
    public static String token(Iterable<ItemStack> items) {
        StringBuilder out = new StringBuilder();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            int tint = item.getItemMeta() instanceof LeatherArmorMeta leather ? leather.getColor().asRGB() : -1;
            String pattern = null, material = null;
            if (item.getItemMeta() instanceof ArmorMeta armor && armor.getTrim() != null) {
                pattern = armor.getTrim().getPattern().getKey().getKey();
                material = armor.getTrim().getMaterial().getKey().getKey();
            }
            if (!out.isEmpty()) out.append(' ');
            out.append(new Piece(item.getType().getKey().getKey(), tint, pattern, material).token());
        }
        return out.toString();
    }

    /** The pieces written in {@code words}; anything unreadable is left out. */
    static List<Piece> parse(@Nullable String words) {
        List<Piece> out = new ArrayList<>();
        if (words == null) return out;
        for (String word : words.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (word.isEmpty()) continue;
            String trimPattern = null, trimMaterial = null;
            int at = word.indexOf('@');
            if (at >= 0) {
                String[] trim = word.substring(at + 1).split(":", 2);
                if (trim.length == 2 && clean(trim[0]) && clean(trim[1])) {
                    trimPattern = trim[0];
                    trimMaterial = trim[1];
                }
                word = word.substring(0, at);
            }
            int tint = -1;
            int hash = word.indexOf('#');
            if (hash >= 0) {
                try {
                    tint = Integer.parseInt(word.substring(hash + 1), 16) & 0xFFFFFF;
                } catch (NumberFormatException ignored) {
                    // No colour: undyed leather.
                }
                word = word.substring(0, hash);
            }
            if (word.startsWith("minecraft:")) word = word.substring("minecraft:".length());
            if (clean(word)) out.add(new Piece(word, tint, trimPattern, trimMaterial));
        }
        return out;
    }

    /** Only names a texture path can be made of: nothing reaches outside the cache. */
    private static boolean clean(String name) {
        return !name.isEmpty() && name.chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_');
    }

    /** The textures of what {@code words} lists. Reads the disk. */
    static Outfit resolve(String words) {
        List<BufferedImage> head = new ArrayList<>(), chest = new ArrayList<>(), legs = new ArrayList<>(),
                feet = new ArrayList<>();
        Pictures.Picture hand = null;
        for (Piece piece : parse(words)) {
            String name = piece.material();
            List<BufferedImage> slot = name.endsWith("_helmet") ? head : name.endsWith("_chestplate") ? chest
                    : name.endsWith("_leggings") ? legs : name.endsWith("_boots") ? feet : null;
            if (slot == null) {
                if (hand == null) hand = held(name);
                continue;
            }
            boolean leggings = slot == legs;
            String asset = asset(name);
            BufferedImage base = layer(asset, leggings, "");
            if (base == null) continue;
            if (piece.tint() >= 0 || asset.equals("leather")) {
                base = tinted(base, piece.tint() >= 0 ? piece.tint() : 0xA06540);
                slot.add(base);
                BufferedImage overlay = layer(asset, leggings, "_overlay");
                if (overlay != null) slot.add(overlay);
            } else {
                slot.add(base);
            }
            if (piece.trimPattern() != null) {
                BufferedImage trim = trim(piece.trimPattern(), piece.trimMaterial(), asset, leggings);
                if (trim != null) slot.add(trim);
            }
        }
        return new Outfit(head, chest, legs, feet, hand);
    }

    private static Pictures.Picture held(String name) {
        try {
            Material material = Material.matchMaterial(name);
            return material == null || !material.isItem() ? null : VanillaSprites.of(new ItemStack(material));
        } catch (RuntimeException | LinkageError noServer) {
            return null;
        }
    }

    /** The texture name of an armour's material: {@code golden_boots} is {@code gold}. */
    static String asset(String material) {
        String kind = material.substring(0, material.lastIndexOf('_'));
        return switch (kind) {
            case "golden" -> "gold";
            case "turtle" -> "turtle_scute";
            default -> kind;
        };
    }

    /** An armour layer: 1.21.2+ keeps it under {@code entity/equipment}, earlier versions under {@code models/armor}. */
    private static @Nullable BufferedImage layer(String asset, boolean leggings, String suffix) {
        String old = asset.equals("turtle_scute") ? "turtle" : asset;
        return VanillaSprites.texture(
                "entity/equipment/" + (leggings ? "humanoid_leggings/" : "humanoid/") + asset + suffix,
                "models/armor/" + old + (leggings ? "_layer_2" : "_layer_1") + suffix);
    }

    /**
     * A trim pattern in its material's colours: the pattern is drawn in the greys of {@code trim_palette}, each
     * swapped for the same place in the material's palette (its darker one on armour of the same material).
     */
    private static @Nullable BufferedImage trim(String pattern, String material, String asset, boolean leggings) {
        BufferedImage shape = VanillaSprites.texture(
                "trims/entity/" + (leggings ? "humanoid_leggings/" : "humanoid/") + pattern,
                "trims/models/armor/" + pattern + (leggings ? "_leggings" : ""));
        BufferedImage key = VanillaSprites.texture("trims/color_palettes/trim_palette");
        BufferedImage colours = asset.equals(material) ? VanillaSprites.texture(
                "trims/color_palettes/" + material + "_darker", "trims/color_palettes/" + material)
                : VanillaSprites.texture("trims/color_palettes/" + material);
        if (shape == null || key == null || colours == null) return null;
        int n = Math.min(key.getWidth(), colours.getWidth());
        BufferedImage out = new BufferedImage(shape.getWidth(), shape.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < shape.getHeight(); y++) {
            for (int x = 0; x < shape.getWidth(); x++) {
                int p = shape.getRGB(x, y);
                if ((p >>> 24) < 128) continue;
                int mapped = p;
                for (int i = 0; i < n; i++) {
                    if ((key.getRGB(i, 0) & 0xFFFFFF) == (p & 0xFFFFFF)) {
                        mapped = colours.getRGB(i, 0);
                        break;
                    }
                }
                out.setRGB(x, y, mapped | 0xFF000000);
            }
        }
        return out;
    }

    private static BufferedImage tinted(BufferedImage image, int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int p = image.getRGB(x, y);
                out.setRGB(x, y, (p & 0xFF000000) | ((p >> 16 & 0xFF) * r / 255) << 16
                        | ((p >> 8 & 0xFF) * g / 255) << 8 | (p & 0xFF) * b / 255);
            }
        }
        return out;
    }
}
