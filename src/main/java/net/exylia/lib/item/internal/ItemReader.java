package net.exylia.lib.item.internal;

import net.exylia.lib.item.Appearance;
import net.exylia.lib.item.Banner;
import net.exylia.lib.item.Consumable;
import net.exylia.lib.item.Item;
import net.exylia.lib.item.Modifier;
import net.exylia.lib.item.Problems;
import net.exylia.lib.item.Potion;
import net.exylia.lib.item.Trim;
import net.exylia.lib.item.Traits;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a configuration section into a definition.
 *
 * <p>Accepts every spelling deployed files are written in, because there are
 * thousands of them across the ecosystem and migrating them was never on the
 * table. Where two spellings exist for one thing, either works and neither is
 * preferred.
 *
 * <p>Two things ExyliaCommons got wrong are fixed rather than reproduced, and
 * both are noted where they happen: {@code flags} was parsed nowhere at all, and
 * {@code hide-attributes} combined two <em>defaults</em> with an OR, so writing
 * {@code false} could not turn it off.
 */
public final class ItemReader {

    private ItemReader() {
    }

    /**
     * Reads an item.
     *
     * @param section  the section describing it
     * @param problems where to report parts that could not be read
     * @return the definition
     */
    public static Item read(ConfigurationSection section, Problems problems) {
        return Item.of(section.getString("material", "STONE"))
                .name(section.getString("name"))
                .displayName(section.getString("display-name", section.getString("display_name")))
                .lore(lore(section))
                .amount(amount(section))
                .appearance(appearance(section))
                .enchantments(enchantments(section, problems))
                .traits(traits(section, problems))
                .build();
    }

    /**
     * Reads the lore.
     *
     * <p>{@code <nl>} splits one entry into several lines, which is how a long
     * description written on one YAML line becomes a readable tooltip.
     */
    private static List<String> lore(ConfigurationSection section) {
        List<String> written = lines(section, "lore");
        if (written.isEmpty()) {
            return List.of();
        }
        List<String> lore = new ArrayList<>(written.size());
        for (String line : written) {
            if (line.contains("<nl>")) {
                lore.addAll(List.of(line.split("<nl>", -1)));
            } else {
                lore.add(line);
            }
        }
        return lore;
    }

    /** Reads the stack size, which may be a number or a placeholder for one. */
    private static String amount(ConfigurationSection section) {
        if (!section.contains("amount")) {
            return "1";
        }
        return section.isInt("amount")
                ? String.valueOf(section.getInt("amount"))
                : section.getString("amount", "1");
    }

    private static Appearance appearance(ConfigurationSection section) {
        return Appearance.builder()
                .glow(flag(section, "glow", "glowing"))
                .hideTooltip(flag(section, "hide-tooltip", "hide_tooltip"))
                // Commons wrote getBoolean(a, true) || getBoolean(b, true), whose
                // value is true whatever the file says. Writing false now turns
                // it off, which is what everyone who wrote it expected.
                .hideAttributes(flag(section, "hide-attributes", "hide_attributes"))
                .unbreakable(section.getBoolean("unbreakable", false))
                .modelData(number(section, -1, "custom-model-data", "custom_model_data"))
                .maxStackSize(number(section, -1, "max_stack_size", "max-stack-size",
                        "maxStackSize"))
                // Never parsed by commons, so fifteen files asking to hide
                // enchantment lines have been showing them for years.
                .flags(lines(section, "flags", "item-flags", "item_flags"))
                .model(first(section, "item_model", "item-model"))
                .tooltipStyle(first(section, "tooltip_style", "tooltip-style"))
                .build();
    }

    /**
     * Reads enchantments.
     *
     * <p>Written as a section, {@code EFFICIENCY: 5}, or as a list of
     * {@code NAME:level} entries. Both are deployed.
     */
    private static Map<String, Integer> enchantments(ConfigurationSection section,
                                                     Problems problems) {
        if (!section.contains("enchantments")) {
            return Map.of();
        }
        Map<String, Integer> enchantments = new LinkedHashMap<>();
        ConfigurationSection written = section.getConfigurationSection("enchantments");
        if (written != null) {
            for (String name : written.getKeys(false)) {
                Object level = written.get(name);
                Integer parsed = level(String.valueOf(level));
                if (parsed == null) {
                    problems.found("enchantment " + name,
                            "the level \"" + level + "\" is not a number");
                    continue;
                }
                enchantments.put(name, parsed);
            }
            return enchantments;
        }
        for (String entry : section.getStringList("enchantments")) {
            int separator = Math.max(entry.lastIndexOf(':'), entry.lastIndexOf('|'));
            if (separator < 0) {
                enchantments.put(entry.trim(), 1);
                continue;
            }
            String name = entry.substring(0, separator).trim();
            String level = entry.substring(separator + 1).trim();
            Integer parsed = level(level);
            if (parsed == null) {
                problems.found("enchantment " + name,
                        "the level \"" + level + "\" is not a number");
                continue;
            }
            enchantments.put(name, parsed);
        }
        return enchantments;
    }

    private static Integer level(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Traits traits(ConfigurationSection section, Problems problems) {
        return Traits.builder()
                .potion(potion(section))
                .trim(trim(section))
                .banner(banner(section, problems))
                .consumable(consumable(section))
                .modifiers(modifiers(section, problems))
                .data(data(section))
                .build();
    }

    /**
     * Reads potion contents.
     *
     * <p>Two layouts: a {@code potion} section, and the flatter keys an older
     * generation of files used. {@code upgraded} and {@code extended} are folded
     * into the base type here rather than carried around, because that is what
     * they mean: {@code HEALING} upgraded is {@code STRONG_HEALING}.
     */
    private static Potion potion(ConfigurationSection section) {
        ConfigurationSection potion = section.getConfigurationSection("potion");
        if (potion != null) {
            return new Potion(
                    strength(potion.getString("base_type", potion.getString("base")),
                            potion.getBoolean("upgraded", false),
                            potion.getBoolean("extended", false)),
                    potion.getString("color"),
                    effects(potion.getList("custom_effects")));
        }
        if (!section.contains("potion_effects") && !section.contains("base_potion_type")
                && !section.contains("potion_color")) {
            return null;
        }
        return new Potion(section.getString("base_potion_type"),
                section.getString("potion_color"),
                effects(section.getList("potion_effects")));
    }

    /**
     * Applies {@code upgraded} and {@code extended} to a base potion type.
     *
     * <p>Vanilla spells the variants as separate types, so a file saying
     * {@code HEALING} with {@code upgraded: true} means {@code STRONG_HEALING}.
     * Commons carried the flags along and then dropped them, which is why a
     * refill kit configured for Instant Health II handed out Instant Health I.
     */
    private static String strength(String base, boolean upgraded, boolean extended) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String name = base.trim();
        if (upgraded && !name.toUpperCase(java.util.Locale.ROOT).startsWith("STRONG_")) {
            return "STRONG_" + name;
        }
        if (extended && !name.toUpperCase(java.util.Locale.ROOT).startsWith("LONG_")) {
            return "LONG_" + name;
        }
        return name;
    }

    private static List<Potion.Effect> effects(List<?> written) {
        if (written == null || written.isEmpty()) {
            return List.of();
        }
        List<Potion.Effect> effects = new ArrayList<>(written.size());
        for (Object entry : written) {
            if (!(entry instanceof Map<?, ?> values)) {
                continue;
            }
            Object type = values.get("type");
            if (type == null) {
                continue;
            }
            effects.add(new Potion.Effect(
                    String.valueOf(type),
                    text(values.get("amplifier"), "0"),
                    text(values.get("duration"), "600"),
                    Boolean.parseBoolean(text(values.get("ambient"), "false")),
                    Boolean.parseBoolean(text(values.get("particles"), "true")),
                    Boolean.parseBoolean(text(values.get("icon"), "true"))));
        }
        return effects;
    }

    private static Trim trim(ConfigurationSection section) {
        ConfigurationSection trim = section.getConfigurationSection("armor_trim");
        if (trim == null) {
            trim = section.getConfigurationSection("armor-trim");
        }
        if (trim == null) {
            return null;
        }
        String pattern = trim.getString("pattern");
        String material = trim.getString("material");
        // Half a trim renders nothing at all, so it is not half-kept.
        return pattern == null || material == null ? null : new Trim(pattern, material);
    }

    /**
     * Reads a banner design.
     *
     * <p>{@code banner_design} carries one base64 string, which is how a design
     * a player built in an editor is saved; {@code banner_patterns} spells the
     * same thing out. Both end up here as the same value.
     *
     * <p>{@code banner_design} may also be a placeholder, for a row whose
     * design is computed per viewer. There is nothing to decode at this point —
     * the design does not exist yet — so it is kept as a template and decoded
     * when the item is drawn.
     */
    private static Banner banner(ConfigurationSection section, Problems problems) {
        String design = first(section, "banner_design", "banner-design");
        if (design != null && !design.isEmpty()) {
            if (design.indexOf('%') >= 0) {
                return Banner.template(design);
            }
            Banner decoded = BannerCodec.decode(design);
            if (decoded == null) {
                problems.found("banner_design", "could not be decoded");
            }
            return decoded;
        }
        ConfigurationSection patterns = section.getConfigurationSection("banner_patterns");
        if (patterns == null) {
            return null;
        }
        List<Banner.Layer> layers = new ArrayList<>();
        for (Object entry : patterns.getList("patterns", List.of())) {
            if (!(entry instanceof Map<?, ?> values)) {
                continue;
            }
            Object pattern = values.get("pattern");
            Object colour = values.get("color");
            if (pattern != null && colour != null) {
                layers.add(new Banner.Layer(String.valueOf(pattern), String.valueOf(colour)));
            }
        }
        Banner banner = new Banner(patterns.getString("base_color"), layers);
        return banner.isEmpty() ? null : banner;
    }

    private static Consumable consumable(ConfigurationSection section) {
        if (!flag(section, "force-consumable", "force_consumable")) {
            return null;
        }
        return new Consumable(
                (float) decimal(section, Consumable.DEFAULT.seconds(),
                        "consumable-time", "consumable_time"),
                number(section, Consumable.DEFAULT.nutrition(),
                        "consumable-nutrition", "consumable_nutrition"),
                (float) decimal(section, Consumable.DEFAULT.saturation(),
                        "consumable-saturation", "consumable_saturation"),
                firstOr(section, Consumable.DEFAULT.sound(),
                        "consumable-sound", "consumable_sound"));
    }

    private static List<Modifier> modifiers(ConfigurationSection section, Problems problems) {
        List<String> written = lines(section, "attributes");
        if (written.isEmpty()) {
            return List.of();
        }
        List<Modifier> modifiers = new ArrayList<>(written.size());
        for (String line : written) {
            try {
                modifiers.add(Modifier.parse(line));
            } catch (IllegalArgumentException bad) {
                problems.found("attribute \"" + line + "\"", bad.getMessage());
            }
        }
        return modifiers;
    }

    /**
     * Reads values to store on the item.
     *
     * <p>Written as a section of key/value pairs, or as a list of
     * {@code key:value} entries.
     */
    private static Map<String, String> data(ConfigurationSection section) {
        ConfigurationSection written = section.getConfigurationSection("nbt");
        if (written != null) {
            Map<String, String> data = new LinkedHashMap<>();
            for (String key : written.getKeys(false)) {
                data.put(key, text(written.get(key), ""));
            }
            return data;
        }
        if (!section.isList("nbt")) {
            return Map.of();
        }
        Map<String, String> data = new LinkedHashMap<>();
        for (String entry : section.getStringList("nbt")) {
            int separator = entry.indexOf(':');
            if (separator > 0) {
                data.put(entry.substring(0, separator).trim(),
                        entry.substring(separator + 1).trim());
            }
        }
        return data;
    }

    /**
     * Reads a flag written either way.
     *
     * <p>Either spelling turns it on, and either spelling can turn it off. The
     * old parser combined the two <em>defaults</em>, so a flag whose default was
     * true could not be disabled by writing one spelling — you had to know to
     * write both.
     */
    private static boolean flag(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            if (section.contains(key)) {
                return section.getBoolean(key);
            }
        }
        return false;
    }

    private static int number(ConfigurationSection section, int fallback, String... keys) {
        for (String key : keys) {
            if (section.contains(key)) {
                return section.getInt(key, fallback);
            }
        }
        return fallback;
    }

    private static double decimal(ConfigurationSection section, double fallback, String... keys) {
        for (String key : keys) {
            if (section.contains(key)) {
                return section.getDouble(key, fallback);
            }
        }
        return fallback;
    }

    private static String first(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            if (section.contains(key)) {
                String value = section.getString(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String firstOr(ConfigurationSection section, String fallback, String... keys) {
        String value = first(section, keys);
        return value == null ? fallback : value;
    }

    /** Reads a key that may hold one line or a list of them. */
    private static List<String> lines(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            if (!section.contains(key)) {
                continue;
            }
            if (section.isString(key)) {
                String single = section.getString(key, "");
                return single.isEmpty() ? List.of() : List.of(single);
            }
            List<String> list = section.getStringList(key);
            if (!list.isEmpty()) {
                return list;
            }
        }
        return List.of();
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
