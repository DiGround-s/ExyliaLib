package net.exylia.lib.item.internal;

import net.exylia.lib.effect.Effects;
import net.exylia.lib.item.Banner;
import net.exylia.lib.item.Modifier;
import net.exylia.lib.item.Potion;
import net.exylia.lib.item.Traits;
import net.exylia.lib.item.Trim;
import net.kyori.adventure.key.Key;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Writes the material-specific parts onto a finished item.
 *
 * <p>Everything here is written against the API paper-api 1.21.4 exposes.
 * ExyliaCommons reached all of this through reflection — two hundred lines of
 * it for consumables alone — because it had to run on servers older than the
 * data components. That is no longer true, so the reflection is gone and what is
 * left is short enough to read.
 *
 * <p>A trait that does not fit its material does nothing. Setting a potion on a
 * sword is a leftover key in a config file, not a reason to fail while drawing
 * a menu.
 */
public final class TraitApplier {

    private TraitApplier() {
    }

    /**
     * Applies every trait an item has.
     *
     * @param item     the item to write onto
     * @param traits   what to write
     * @param owner    whose namespace stored values go under, or {@code null}
     * @param resolve  how to resolve placeholders in a value
     * @param problems where to report parts that could not be applied
     */
    public static void apply(ItemStack item, Traits traits, Plugin owner,
                             UnaryOperator<String> resolve, Reporter problems) {
        if (traits.isEmpty()) {
            return;
        }
        if (traits.potion() != null) {
            potion(item, traits.potion(), resolve, problems);
        }
        if (traits.trim() != null) {
            trim(item, traits.trim(), resolve, problems);
        }
        if (traits.banner() != null) {
            banner(item, traits.banner(), resolve, problems);
        }
        if (traits.consumable() != null) {
            consumable(item, traits.consumable(), problems);
        }
        if (!traits.modifiers().isEmpty()) {
            modifiers(item, traits.modifiers(), problems);
        }
        if (!traits.data().isEmpty() && owner != null) {
            data(item, traits.data(), owner, resolve);
        }
    }

    private static void potion(ItemStack item, Potion potion, UnaryOperator<String> resolve,
                               Reporter problems) {
        if (!(item.getItemMeta() instanceof PotionMeta meta)) {
            return;
        }
        if (potion.base() != null) {
            PotionType type = Registries.potion(potion.base());
            if (type == null) {
                problems.found("potion", "there is no potion type called \"" + potion.base() + "\"");
            } else {
                meta.setBasePotionType(type);
            }
        }
        if (potion.colour() != null) {
            Color colour = Registries.colour(resolve.apply(potion.colour()));
            if (colour != null) {
                meta.setColor(colour);
            }
        }
        for (Potion.Effect effect : potion.effects()) {
            PotionEffectType type = Registries.effect(resolve.apply(effect.type()));
            if (type == null) {
                problems.found("potion effect", "there is no effect called \"" + effect.type() + "\"");
                continue;
            }
            Integer amplifier = whole(resolve.apply(effect.amplifier()));
            Integer duration = whole(resolve.apply(effect.duration()));
            if (amplifier == null || duration == null) {
                problems.found("potion effect " + effect.type(),
                        "the amplifier or duration is not a whole number");
                continue;
            }
            meta.addCustomEffect(new PotionEffect(type, duration, amplifier,
                    effect.ambient(), effect.particles(), effect.icon()), true);
        }
        item.setItemMeta(meta);
    }

    private static void trim(ItemStack item, Trim trim, UnaryOperator<String> resolve,
                             Reporter problems) {
        if (!(item.getItemMeta() instanceof ArmorMeta meta)) {
            return;
        }
        String patternName = resolve.apply(trim.pattern());
        String materialName = resolve.apply(trim.material());
        if (patternName.isBlank() || materialName.isBlank()) {
            // A trim editor showing an unset slot writes empty here; that is a
            // piece of armour with no trim, not a mistake.
            return;
        }
        TrimPattern pattern = Registries.trimPattern(patternName);
        TrimMaterial material = Registries.trimMaterial(materialName);
        if (pattern == null || material == null) {
            problems.found("armor_trim", "unknown pattern \"" + patternName
                    + "\" or material \"" + materialName + "\"");
            return;
        }
        meta.setTrim(new ArmorTrim(material, pattern));
        item.setItemMeta(meta);
    }

    /**
     * Applies a banner design.
     *
     * <p>Two different metas depending on what it is drawn on: a banner carries
     * the patterns itself, a shield carries a banner block state inside it.
     */
    private static void banner(ItemStack item, Banner banner, UnaryOperator<String> resolve,
                               Reporter problems) {
        List<Pattern> layers = layers(banner, resolve, problems);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BannerMeta bannerMeta) {
            bannerMeta.setPatterns(layers);
            item.setItemMeta(bannerMeta);
            return;
        }
        if (!(meta instanceof BlockStateMeta blockMeta)
                || !(blockMeta.getBlockState() instanceof org.bukkit.block.Banner state)) {
            return;
        }
        if (banner.baseColor() != null) {
            DyeColor base = Registries.dye(resolve.apply(banner.baseColor()));
            if (base != null) {
                state.setBaseColor(base);
            }
        }
        state.setPatterns(layers);
        state.update();
        blockMeta.setBlockState(state);
        item.setItemMeta(blockMeta);
    }

    private static List<Pattern> layers(Banner banner, UnaryOperator<String> resolve,
                                        Reporter problems) {
        List<Pattern> layers = new ArrayList<>(banner.patterns().size());
        for (Banner.Layer layer : banner.patterns()) {
            PatternType pattern = Registries.pattern(resolve.apply(layer.pattern()));
            DyeColor colour = Registries.dye(resolve.apply(layer.colour()));
            if (pattern == null || colour == null) {
                problems.found("banner pattern", "unknown pattern \"" + layer.pattern()
                        + "\" or colour \"" + layer.colour() + "\"");
                continue;
            }
            layers.add(new Pattern(colour, pattern));
        }
        return layers;
    }

    /**
     * Makes anything edible.
     *
     * <p>Only expressible as a data component, so it is refused rather than
     * approximated on a server without them. Saying so is the point: an item
     * silently not being edible is a bug report about the plugin.
     */
    private static void consumable(ItemStack item, net.exylia.lib.item.Consumable consumable,
                                   Reporter problems) {
        if (!ComponentSupport.available()) {
            problems.found("force-consumable",
                    "this server cannot make items edible; Paper 1.21 or newer is needed");
            return;
        }
        Key sound = soundKey(consumable.sound());
        if (sound == null) {
            problems.found("consumable-sound",
                    "\"" + consumable.sound() + "\" is not a sound key");
        }
        Components.consumable(item, consumable, sound);
    }

    /**
     * Reads a sound as a key.
     *
     * <p>Asked of the effect module rather than worked out here. The mapping is
     * not mechanical — {@code ENTITY_PLAYER_LEVELUP} is
     * {@code entity.player.levelup} but {@code BLOCK_NOTE_BLOCK_PLING} keeps its
     * underscore — and a second implementation of that rule is a second chance
     * to get it wrong. A live server has already heard what happens.
     */
    private static Key soundKey(String sound) {
        try {
            return Key.key(Effects.sound(sound).key());
        } catch (RuntimeException notAKey) {
            return null;
        }
    }

    /**
     * Writes attribute modifiers.
     *
     * <p>Through the data component where there is one, and through the meta
     * otherwise. Unlike the consumable there is a portable way to do this, so a
     * Spigot server gets the modifiers rather than a warning.
     */
    private static void modifiers(ItemStack item, List<Modifier> modifiers, Reporter problems) {
        if (ComponentSupport.available()) {
            Components.modifiers(item, modifiers, problems);
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        for (Modifier modifier : modifiers) {
            Attribute attribute = Registries.attribute(modifier.key());
            if (attribute == null) {
                problems.found("attribute",
                        "there is no attribute called \"" + modifier.attribute() + "\"");
                continue;
            }
            meta.addAttributeModifier(attribute, new AttributeModifier(
                    new NamespacedKey("exylia", "item_" + modifier.key()),
                    modifier.amount(), AttributeModifier.Operation.ADD_NUMBER,
                    org.bukkit.inventory.EquipmentSlotGroup.ANY));
        }
        item.setItemMeta(meta);
    }

    /**
     * Stores values on the item under the owning plugin's namespace.
     *
     * <p>Typed by what the value looks like, so a plugin reading back an
     * {@code uses} of {@code 3} gets an integer rather than the string
     * {@code "3"}.
     */
    private static void data(ItemStack item, Map<String, String> values, Plugin owner,
                             UnaryOperator<String> resolve) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        var container = meta.getPersistentDataContainer();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            NamespacedKey key;
            try {
                key = new NamespacedKey(owner, entry.getKey());
            } catch (IllegalArgumentException notAKey) {
                continue;
            }
            String value = resolve.apply(entry.getValue());
            if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                container.set(key, PersistentDataType.BOOLEAN, Boolean.parseBoolean(value));
                continue;
            }
            Integer whole = whole(value);
            if (whole != null) {
                container.set(key, PersistentDataType.INTEGER, whole);
                continue;
            }
            try {
                container.set(key, PersistentDataType.DOUBLE, Double.parseDouble(value));
            } catch (NumberFormatException notANumber) {
                container.set(key, PersistentDataType.STRING, value);
            }
        }
        item.setItemMeta(meta);
    }

    private static Integer whole(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Where a part that could not be applied is reported. */
    @FunctionalInterface
    public interface Reporter {
        /** Reports a part of an item that could not be applied. */
        void found(String where, String problem);
    }
}
