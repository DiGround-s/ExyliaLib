package net.exylia.lib.util.loot.internal;

import net.exylia.lib.item.Source;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * The one part of the module that needs a running server.
 *
 * <p>Everything else a loot table decides — which lines come up, how many of
 * each, what a stored row means, what a menu labels it — is decided in terms of
 * a snapshot string and a count, which is why all of it can be tested without
 * one. Only this turns those into an {@link ItemStack}.
 *
 * <p>Lives in {@code internal} rather than the public API: a plugin has no
 * business replacing how a loot item is built, and a test does.
 */
public interface LootItems {

    /**
     * Builds the item a written token names.
     *
     * <p>The grammar is the one loot configs already use: a material name, or a
     * potion written {@code POTION:HEALING}, {@code SPLASH:STRENGTH},
     * {@code LINGERING:REGENERATION} or {@code TIPPED:SLOWNESS}.
     *
     * @param token the token, already uppercased
     * @return the item, or {@code null} when the token names nothing
     */
    @Nullable ItemStack of(@NotNull String token);

    /**
     * Serialises an item the way a loot table stores one.
     *
     * @param item the item
     * @return the stored form
     */
    @NotNull String snapshot(@NotNull ItemStack item);

    /**
     * Rebuilds an item from what a loot table stored.
     *
     * @param snapshot the stored form
     * @return the item, or {@code null} when the string names nothing
     */
    @Nullable ItemStack build(@NotNull String snapshot);

    /** The real one. */
    LootItems BUKKIT = new LootItems() {

        @Override
        public @Nullable ItemStack of(@NotNull String token) {
            int separator = token.indexOf(':');
            if (separator > 0) {
                Material container = potionContainer(token.substring(0, separator));
                PotionType type = potionType(token.substring(separator + 1));
                return container == null || type == null ? null : potion(container, type);
            }
            Material material = Material.matchMaterial(token);
            return material == null || !material.isItem() ? null : new ItemStack(material);
        }

        @Override
        public @NotNull String snapshot(@NotNull ItemStack item) {
            // Byte-identical to ExyliaCommons' ItemSnapshot.from(ItemStack):
            // the same serializeAsBytes under the same standard Base64 under the
            // same bytes: prefix, down to the "AIR" it wrote for nothing. A
            // table written by this library therefore diffs clean against one
            // written by the old module.
            if (item.getType() == Material.AIR) {
                return "AIR";
            }
            try {
                return "bytes:" + Base64.getEncoder().encodeToString(item.serializeAsBytes());
            } catch (RuntimeException unwritable) {
                return item.getType().name();
            }
        }

        @Override
        public @Nullable ItemStack build(@NotNull String snapshot) {
            Source source = Source.of(snapshot);
            if (source instanceof Source.OfSnapshot bytes) {
                try {
                    return ItemStack.deserializeBytes(Base64.getDecoder().decode(bytes.base64()));
                } catch (RuntimeException unreadable) {
                    return null;
                }
            }
            if (source instanceof Source.OfMaterial material) {
                Material type = Material.matchMaterial(material.raw());
                return type == null ? null : new ItemStack(type);
            }
            // A head. The skull module owns turning one of those into an item,
            // and a loot table that stores a head is drawn far more often than
            // it is rolled.
            return new ItemStack(Material.PLAYER_HEAD);
        }
    };

    /**
     * The bottle a potion prefix names.
     *
     * @param prefix the part before the colon
     * @return the container material, or {@code null} when it is not one
     */
    static @Nullable Material potionContainer(@NotNull String prefix) {
        return switch (prefix) {
            case "POTION" -> Material.POTION;
            case "SPLASH" -> Material.SPLASH_POTION;
            case "LINGERING" -> Material.LINGERING_POTION;
            case "TIPPED" -> Material.TIPPED_ARROW;
            default -> null;
        };
    }

    /**
     * The other name the same potion has gone by.
     *
     * <p>Mojang renamed half of these between versions and the configs out
     * there are written in both vocabularies. Kept verbatim from ExyliaCommons,
     * because a table that resolved on the old module has to resolve here.
     *
     * <p>Pure, and public so a test can prove the pairs without a registry.
     *
     * @param name the name as written, uppercase
     * @return the other spelling, or {@code null} when there is not one
     */
    static @Nullable String alias(@NotNull String name) {
        return ALIASES.get(name);
    }

    /** Both directions of every rename, so either spelling finds the other. */
    Map<String, String> ALIASES = Map.of(
            "SPEED", "SWIFTNESS",
            "SWIFTNESS", "SPEED",
            "INSTANT_HEAL", "HEALING",
            "HEALING", "INSTANT_HEAL",
            "INSTANT_DAMAGE", "HARMING",
            "HARMING", "INSTANT_DAMAGE",
            "JUMP", "LEAPING",
            "LEAPING", "JUMP",
            "REGEN", "REGENERATION");

    /**
     * A potion type by either of its names.
     *
     * @param name the name as written
     * @return the type, or {@code null}
     */
    private static @Nullable PotionType potionType(@NotNull String name) {
        PotionType type = registered(name);
        if (type != null) {
            return type;
        }
        String alias = alias(name);
        return alias == null ? null : registered(alias);
    }

    /**
     * Looks a potion type up in the registry rather than with {@code valueOf}.
     *
     * <p>Several of these types stopped being enums in 1.21 and the registry
     * form works on both sides of that change.
     */
    @SuppressWarnings("deprecation")
    private static @Nullable PotionType registered(@NotNull String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return null;
        }
        NamespacedKey namespaced = NamespacedKey.fromString(key);
        return namespaced == null ? null : Registry.POTION.get(namespaced);
    }

    private static @NotNull ItemStack potion(@NotNull Material container, @NotNull PotionType type) {
        ItemStack item = new ItemStack(container);
        if (item.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(type);
            item.setItemMeta(meta);
        }
        return item;
    }
}
