package net.exylia.lib.ui.internal;

import net.exylia.lib.action.ActionTemplate;
import net.exylia.lib.skull.SkullSource;
import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.Slots;
import net.exylia.lib.ui.UiAnimationSpec;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.ui.UiSounds;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads a menu file into a compiled definition.
 *
 * <p>Accepts the format existing menus are written in, unchanged. That is a
 * hard requirement rather than a nicety: there are hundreds of these files
 * across the ecosystem, and a migration is a day of work plus a long tail of
 * files somebody forgot.
 *
 * <p>Where the old parser disagreed with itself, this one picks the safer
 * behaviour and says so: a malformed slot is an error wherever it appears,
 * rather than an error in one section and a silent nothing in another.
 */
public final class MenuLoader {

    /**
     * Where a problem with one part of a menu is reported.
     *
     * <p>A menu is a screen made of many independent pieces, and one bad
     * button should not stop the other fifty from working. The old system
     * agreed, but reported nothing: a mistyped action failed silently when
     * somebody pressed it, so a broken button looked exactly like a working
     * one until a player complained.
     */
    @FunctionalInterface
    public interface Problems {
        /** Reports something wrong with a part of a menu. */
        void found(String where, String problem);

        /** Ignores problems. For callers that only want the definition. */
        Problems IGNORED = (where, problem) -> { };
    }

    private MenuLoader() {
    }

    /**
     * Compiles a menu.
     *
     * @param id       the id to give it, such as {@code practice:queue_kits}
     * @param config   the file's root section
     * @param compiler how to compile an action string
     * @param defaults the sounds to fall back to
     * @return the compiled definition
     */
    public static UiDefinition load(String id, ConfigurationSection config,
                                    Function<String, ActionTemplate> compiler,
                                    UiSounds defaults) {
        return load(id, config, compiler, defaults, Problems.IGNORED);
    }

    /**
     * Compiles a menu, reporting anything wrong with its parts.
     *
     * <p>Structural mistakes — a slot outside the menu, a size that is not a
     * row count — are errors: they mean the file does not describe a menu, and
     * guessing would hide the mistake. A mistyped action is not structural, so
     * it becomes a dead button and a line in the console.
     *
     * @param id       the id to give it
     * @param config   the file's root section
     * @param compiler how to compile an action string
     * @param defaults the sounds to fall back to
     * @param problems where to report bad parts
     * @return the compiled definition
     */
    public static UiDefinition load(String id, ConfigurationSection config,
                                    Function<String, ActionTemplate> compiler,
                                    UiSounds defaults, Problems problems) {
        return read(id, config, new Binder(compiler, problems), defaults);
    }

    /**
     * Compiles what a button does, turning a bad line into a dead button.
     *
     * <p>Menus in the wild contain lines like
     * {@code "any: player: practice:open_regions"} — a command written under
     * {@code actions}. That button has never worked; the difference now is
     * that somebody is told, and the other fifty buttons still open.
     */
    private record Binder(Function<String, ActionTemplate> compiler, Problems problems) {

        /** Compiles an action, falling back to a no-op. */
        ActionTemplate action(String raw) {
            try {
                return compiler.apply(raw);
            } catch (IllegalArgumentException bad) {
                problems.found("action \"" + raw + "\"", bad.getMessage());
                return compiler.apply("none");
            }
        }

        /** Fills bindings from an item's {@code actions} and {@code commands}. */
        void bind(ClickBindings.Builder bindings, ConfigurationSection section) {
            for (String line : actions(section)) {
                bindings.add(line, this::action);
            }
            for (String line : lines(section, "commands")) {
                try {
                    bindings.addCommand(line);
                } catch (IllegalArgumentException bad) {
                    problems.found("command \"" + line + "\"", bad.getMessage());
                }
            }
        }
    }

    private static UiDefinition read(String id, ConfigurationSection config,
                                     Binder binder, UiSounds defaults) {
        String title = config.getString("title", "Menu");
        UiDefinition.UiKind kind = kindOf(config.getString("type", "CHEST"));
        int size = size(config, kind);

        Map<Integer, UiItem> items = new LinkedHashMap<>();
        readItems(config.getConfigurationSection("items"), binder, size, items);

        List<UiItem> fillers = readFillers(config.getConfigurationSection("filler"), binder);
        UiDefinition.Pagination pagination =
                readPagination(config.getConfigurationSection("pagination"), binder, size);

        List<Integer> inputSlots = slots(config, "editable_slots");
        if (inputSlots.isEmpty()) {
            inputSlots = slots(config, "input-slots");
        }

        UiSounds sounds = defaults;
        ConfigurationSection soundSection = config.getConfigurationSection("sounds");
        if (soundSection != null) {
            sounds = UiSounds.of(values(soundSection), defaults);
        }

        return new UiDefinition(id, title, kind, size, items, fillers, pagination, inputSlots,
                sounds, animation(config, "animation"),
                config.getStringList("open-actions"),
                config.getStringList("close-actions"),
                config.getString("parent"));
    }

    /**
     * Reads the container kind.
     *
     * <p>The old {@code type} values described the container and whether it
     * paginated at the same time. Everything that ended in pagination is a
     * chest here; whether it paginates is decided by whether the file has a
     * {@code pagination} section, which it always did anyway.
     */
    private static UiDefinition.UiKind kindOf(String raw) {
        String normalised = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalised) {
            case "", "SIMPLE", "PAGINATION", "MULTI_PAGINATION", "FULL_INVENTORY",
                 "PAGINATION_FULL", "MULTI_PAGINATION_FULL", "ITEM_INPUT", "CHEST" ->
                    UiDefinition.UiKind.CHEST;
            default -> {
                try {
                    yield UiDefinition.UiKind.valueOf(normalised);
                } catch (IllegalArgumentException unknown) {
                    yield UiDefinition.UiKind.CHEST;
                }
            }
        };
    }

    private static int size(ConfigurationSection config, UiDefinition.UiKind kind) {
        if (!kind.isSizeConfigurable()) {
            return kind.sizeOf(0);
        }
        int size = config.getInt("size", rows(config));
        if (size % 9 != 0 || size < 9 || size > 54) {
            throw new IllegalArgumentException(
                    "Menu size must be a multiple of 9 between 9 and 54, got " + size);
        }
        return size;
    }

    /** {@code rows: 3} is the friendlier spelling of {@code size: 27}. */
    private static int rows(ConfigurationSection config) {
        int rows = config.getInt("rows", 0);
        return rows > 0 ? rows * 9 : 54;
    }

    private static void readItems(ConfigurationSection section,
                                  Binder binder,
                                  int size, Map<Integer, UiItem> into) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection itemSection = section.getConfigurationSection(key);
            if (itemSection == null) {
                continue;
            }
            UiItem item = readItem(itemSection, binder);
            for (int slot : itemSlots(itemSection, key)) {
                if (slot < 0 || slot >= size) {
                    throw new IllegalArgumentException("Item \"" + key + "\" uses slot " + slot
                            + ", outside a menu of " + size);
                }
                into.put(slot, item);
            }
        }
    }

    private static List<Integer> itemSlots(ConfigurationSection section, String key) {
        boolean hasSlot = section.contains("slot");
        boolean hasSlots = section.contains("slots");
        if (hasSlot && hasSlots) {
            throw new IllegalArgumentException(
                    "Item \"" + key + "\" declares both slot and slots; pick one");
        }
        if (hasSlot) {
            return slots(section, "slot");
        }
        if (hasSlots) {
            return slots(section, "slots");
        }
        throw new IllegalArgumentException("Item \"" + key + "\" has no slot");
    }

    /** Reads a slot expression in any of the forms configuration uses. */
    static List<Integer> slots(ConfigurationSection section, String key) {
        if (!section.contains(key)) {
            return List.of();
        }
        if (section.isInt(key)) {
            return List.of(section.getInt(key));
        }
        if (section.isString(key)) {
            return Slots.parse(section.getString(key, ""));
        }
        if (section.isList(key)) {
            List<Integer> all = new ArrayList<>();
            for (Object entry : section.getList(key, List.of())) {
                if (entry instanceof Number number) {
                    all.add(number.intValue());
                } else if (entry != null) {
                    all.addAll(Slots.parse(String.valueOf(entry)));
                }
            }
            return List.copyOf(all);
        }
        return List.of();
    }

    /** Reads one item definition. */
    static UiItem readItem(ConfigurationSection section,
                           Binder binder) {
        UiItem.Builder builder = UiItem.of(section.getString("material", "STONE"))
                .name(section.getString("name"))
                .lore(section.getStringList("lore"))
                .amount(amount(section))
                .glow(flag(section, "glow", "glowing"))
                .hideTooltip(flag(section, "hide-tooltip", "hide_tooltip"))
                .customModelData(section.getInt("custom-model-data",
                        section.getInt("custom_model_data", -1)))
                .condition(section.getString("condition"))
                .dependsOn(section.getStringList("depends-on"))
                .animation(animation(section, "animation"))
                .itemFlags(section.getStringList("item-flags"))
                .enchantments(enchantments(section));

        head(section, builder);

        ClickBindings.Builder bindings = new ClickBindings.Builder();
        binder.bind(bindings, section);
        return builder.bindings(bindings.build()).build();
    }

    /**
     * Reads the head of an item.
     *
     * <p>All the spellings menus use, because head configuration is where the
     * old system was loosest: a base64 texture, a URL, a player name, or a
     * name with a placeholder in it, which cannot be resolved until the row is
     * drawn.
     */
    private static void head(ConfigurationSection section, UiItem.Builder builder) {
        String texture = first(section, "texture", "head-texture", "skull-texture");
        if (texture != null && !texture.isEmpty()) {
            builder.head(SkullSource.texture(texture));
            return;
        }
        String url = first(section, "head-url", "skull-url");
        if (url != null && !url.isEmpty()) {
            builder.head(SkullSource.url(url));
            return;
        }
        String owner = first(section, "head", "skull", "head-owner", "skull-owner", "owner");
        if (owner == null || owner.isEmpty()) {
            return;
        }
        if (owner.indexOf('%') >= 0) {
            // Whose head it is depends on the row, so it is resolved per render.
            builder.headTemplate(owner);
        } else {
            builder.head(SkullSource.player(owner));
        }
    }

    private static String first(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            if (section.contains(key)) {
                return section.getString(key);
            }
        }
        return null;
    }

    /**
     * Reads a flag written either way.
     *
     * <p>Both spellings are accepted, and either one being true is enough. The
     * old parser combined the two with an OR of two <em>defaults</em>, which
     * meant a flag defaulting to true could not be turned off by writing one
     * spelling — you had to know to write both.
     */
    private static boolean flag(ConfigurationSection section, String dashed, String underscored) {
        if (section.contains(dashed)) {
            return section.getBoolean(dashed);
        }
        if (section.contains(underscored)) {
            return section.getBoolean(underscored);
        }
        return false;
    }

    private static String amount(ConfigurationSection section) {
        if (!section.contains("amount")) {
            return "1";
        }
        return section.isInt("amount")
                ? String.valueOf(section.getInt("amount"))
                : section.getString("amount", "1");
    }

    private static Map<String, Integer> enchantments(ConfigurationSection section) {
        Map<String, Integer> enchantments = new HashMap<>();
        for (String entry : section.getStringList("enchantments")) {
            int separator = entry.lastIndexOf(':');
            if (separator < 0) {
                separator = entry.lastIndexOf('|');
            }
            if (separator < 0) {
                enchantments.put(entry.trim(), 1);
                continue;
            }
            try {
                enchantments.put(entry.substring(0, separator).trim(),
                        Integer.parseInt(entry.substring(separator + 1).trim()));
            } catch (NumberFormatException notALevel) {
                enchantments.put(entry.substring(0, separator).trim(), 1);
            }
        }
        return enchantments;
    }

    /** Reads the actions of an item, accepting a list or a single string. */
    private static List<String> actions(ConfigurationSection section) {
        return lines(section, "actions");
    }

    /** Reads a key that may hold one line or a list of them. */
    private static List<String> lines(ConfigurationSection section, String key) {
        if (!section.contains(key)) {
            return List.of();
        }
        if (section.isString(key)) {
            String single = section.getString(key, "");
            return single.isBlank() ? List.of() : List.of(single);
        }
        return section.getStringList(key);
    }

    private static List<UiItem> readFillers(ConfigurationSection section,
                                            Binder binder) {
        if (section == null) {
            return List.of();
        }
        List<UiItem> fillers = new ArrayList<>();
        for (String key : List.of("global", "border", "pagination")) {
            ConfigurationSection filler = section.getConfigurationSection(key);
            if (filler != null) {
                fillers.add(readItem(filler, binder));
            }
        }
        ConfigurationSection custom = section.getConfigurationSection("custom");
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                ConfigurationSection one = custom.getConfigurationSection(key);
                if (one != null) {
                    fillers.add(readItem(one, binder));
                }
            }
        }
        return fillers;
    }

    private static UiDefinition.Pagination readPagination(ConfigurationSection section,
                                                          Binder binder,
                                                          int size) {
        if (section == null) {
            return null;
        }
        List<Integer> slots = slots(section, "slots");
        if (slots.isEmpty()) {
            return null;
        }
        for (int slot : slots) {
            if (slot < 0 || slot >= size) {
                throw new IllegalArgumentException(
                        "Pagination uses slot " + slot + ", outside a menu of " + size);
            }
        }
        ConfigurationSection template = section.getConfigurationSection("item_template");
        if (template == null) {
            template = section.getConfigurationSection("item-template");
        }
        if (template == null) {
            throw new IllegalArgumentException("Pagination has slots but no item_template");
        }
        ConfigurationSection navigation = section.getConfigurationSection("navigation");
        ConfigurationSection filler = section.getConfigurationSection("filler");
        return new UiDefinition.Pagination(slots, readItem(template, binder),
                placed(navigation, "previous", binder),
                placed(navigation, "next", binder),
                filler == null ? null : readItem(filler, binder));
    }

    private static UiDefinition.Placed placed(ConfigurationSection navigation, String key,
                                              Binder binder) {
        if (navigation == null) {
            return null;
        }
        ConfigurationSection section = navigation.getConfigurationSection(key);
        if (section == null) {
            return null;
        }
        int slot = section.getInt("slot", -1);
        if (slot < 0) {
            return null;
        }
        return new UiDefinition.Placed(slot, readItem(section, binder));
    }

    private static UiAnimationSpec animation(ConfigurationSection section, String key) {
        if (!section.contains(key)) {
            return null;
        }
        if (section.isString(key)) {
            return UiAnimationSpec.of(section.getString(key, "pulse"));
        }
        ConfigurationSection animation = section.getConfigurationSection(key);
        if (animation == null) {
            return null;
        }
        // The old format wrote the open animation under "open".
        if (animation.contains("open") && !animation.contains("type")) {
            return UiAnimationSpec.of(animation.getString("open", "pulse"));
        }
        return UiAnimationSpec.of(values(animation));
    }

    private static Map<String, Object> values(ConfigurationSection section) {
        Map<String, Object> values = new HashMap<>();
        for (String key : section.getKeys(false)) {
            values.put(key, section.get(key));
        }
        return values;
    }
}
