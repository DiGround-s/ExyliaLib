package net.exylia.lib.ui.internal;

import net.exylia.lib.action.ActionTemplate;
import net.exylia.lib.item.Items;
import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.Slots;
import net.exylia.lib.ui.UiAnimationSpec;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.ui.UiFillers;
import net.exylia.lib.ui.UiRefresh;
import net.exylia.lib.ui.UiSection;
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

        UiFillers fillers = readFillers(config.getConfigurationSection("filler"), binder);
        Map<String, UiSection> sections = readSections(config, binder, size);

        List<Integer> inputSlots = slots(config, "editable_slots");
        if (inputSlots.isEmpty()) {
            inputSlots = slots(config, "input-slots");
        }

        UiSounds sounds = readSounds(config, defaults);

        ConfigurationSection refreshSection = config.getConfigurationSection("refresh");
        UiRefresh refresh = refreshSection == null
                ? UiRefresh.NEVER
                : UiRefresh.of(values(refreshSection));

        return new UiDefinition(id, title, kind, size, items, fillers, sections, inputSlots,
                sounds, refresh, animation(config, "animation", binder.problems()),
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

    /**
     * Reads one slot for a caller that numbers its own slots.
     *
     * <p>The overlay module writes items in the same format menus do — the
     * same {@code material}, the same {@code actions}, the same
     * {@code condition} — but places them in the player's inventory rather
     * than in a window, so it reads the slot itself. This is the half the two
     * share, exposed rather than copied.
     *
     * @param section  the item's section
     * @param compiler how to compile an action string
     * @param problems where to report bad parts
     * @return the compiled slot
     * @since 1.79.0
     */
    public static UiItem item(ConfigurationSection section,
                              Function<String, ActionTemplate> compiler,
                              Problems problems) {
        return readItem(section, new Binder(compiler, problems));
    }

    /**
     * Reads one slot.
     *
     * <p>What it looks like is read by the item module, which owns that format
     * and is used by four plugins that never open a menu. What is left here is
     * what only a menu means: clicks, a condition, and what a redraw depends
     * on.
     */
    static UiItem readItem(ConfigurationSection section,
                           Binder binder) {
        ClickBindings.Builder bindings = new ClickBindings.Builder();
        binder.bind(bindings, section);
        return UiItem.of(Items.parse(section, binder.problems()::found))
                .bindings(bindings.build())
                .condition(section.getString("condition"))
                .dependsOn(section.getStringList("depends-on"))
                .build();
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

    /**
     * Reads what fills the slots a menu does not otherwise use.
     *
     * <p>Three jobs, not one list. {@code global} is the background;
     * {@code pagination} is what a short list shows in its empty slots, and
     * usually says something ("no kits available") rather than being another
     * grey pane; {@code custom} is named panels with their own slots.
     */
    private static UiFillers readFillers(ConfigurationSection section, Binder binder) {
        if (section == null) {
            return UiFillers.NONE;
        }
        UiItem global = child(section, binder, "global", "border");
        UiItem pagination = child(section, binder, "pagination");

        List<UiFillers.Panel> panels = new ArrayList<>();
        ConfigurationSection custom = section.getConfigurationSection("custom");
        if (custom != null) {
            for (String key : custom.getKeys(false)) {
                ConfigurationSection one = custom.getConfigurationSection(key);
                if (one == null) {
                    continue;
                }
                List<Integer> where = slots(one, "slots");
                if (where.isEmpty()) {
                    // A panel with nowhere to go would silently do nothing.
                    continue;
                }
                panels.add(new UiFillers.Panel(key, readItem(one, binder), where));
            }
        }
        return new UiFillers(global, pagination, panels);
    }

    /** Reads a child section under whichever of these names is present. */
    private static UiItem child(ConfigurationSection section, Binder binder, String... keys) {
        for (String key : keys) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                return readItem(child, binder);
            }
        }
        return null;
    }

    /**
     * Reads what a menu sounds like.
     *
     * <p>Two spellings, because the files in the wild use one and the tidier
     * one is worth having. A {@code sounds} block names each sound; the older
     * form is a list per sound at the root:
     *
     * <pre>
     * open_sounds:
     *   - "ENTITY_EXPERIENCE_ORB_PICKUP|1.0|1.2"
     * click_sounds:
     *   - "UI_BUTTON_CLICK|1.0|1.5"
     * </pre>
     *
     * <p>Nine hundred and ninety-six menus write {@code open_sounds} and none
     * write a {@code sounds} block, so reading only the latter would have made
     * every menu in the ecosystem silent.
     *
     * <p>A list because that is how they were written, but only the first entry
     * is used: the rest were never played by the old runtime either, and a
     * button that makes four noises at once is not a feature anybody asked for.
     */
    private static UiSounds readSounds(ConfigurationSection config, UiSounds defaults) {
        Map<String, Object> named = new LinkedHashMap<>();
        for (String key : List.of("open", "close", "click", "denied", "failed", "back", "page")) {
            String listed = firstSound(config, key + "_sounds", key + "-sounds");
            if (listed != null) {
                named.put(key, listed);
            }
        }
        // The tidier spelling wins where both are present: somebody who wrote
        // it meant it, and the other form is what they are migrating from.
        ConfigurationSection block = config.getConfigurationSection("sounds");
        if (block != null) {
            named.putAll(values(block));
        }
        return named.isEmpty() ? defaults : UiSounds.of(named, defaults);
    }

    /**
     * The first sound of a list, or the value if it was written as one string.
     *
     * <p>An empty list means silence, and is not the same as no key at all.
     */
    private static String firstSound(ConfigurationSection config, String... keys) {
        for (String key : keys) {
            if (!config.contains(key)) {
                continue;
            }
            if (config.isList(key)) {
                List<String> listed = config.getStringList(key);
                return listed.isEmpty() ? "" : listed.getFirst();
            }
            return config.getString(key, "");
        }
        return null;
    }

    /**
     * Reads the paginated lists of a menu.
     *
     * <p>Two spellings mean the same thing. A {@code pagination} block is one
     * list and becomes a section named {@link UiSection#MAIN}; a
     * {@code sections} block names each of several. A hundred and fifty files
     * use the first and thirteen the second, and neither has to know about the
     * other.
     */
    private static Map<String, UiSection> readSections(ConfigurationSection config,
                                                       Binder binder, int size) {
        Map<String, UiSection> sections = new LinkedHashMap<>();

        UiSection single = readSection(UiSection.MAIN,
                config.getConfigurationSection("pagination"), binder, size);
        if (single != null) {
            sections.put(single.id(), single);
        }

        ConfigurationSection named = config.getConfigurationSection("sections");
        if (named != null) {
            for (String id : named.getKeys(false)) {
                UiSection section = readSection(id,
                        named.getConfigurationSection(id), binder, size);
                if (section != null) {
                    sections.put(id, section);
                }
            }
        }
        return sections;
    }

    /** Reads one paginated list. */
    private static UiSection readSection(String id, ConfigurationSection section,
                                         Binder binder, int size) {
        if (section == null) {
            return null;
        }
        List<Integer> slots = slots(section, "slots");
        if (slots.isEmpty()) {
            return null;
        }
        for (int slot : slots) {
            if (slot < 0 || slot >= size) {
                throw new IllegalArgumentException("Section \"" + id + "\" uses slot " + slot
                        + ", outside a menu of " + size);
            }
        }
        Map<String, UiItem> templates = readTemplates(id, section, binder);
        ConfigurationSection navigation = section.getConfigurationSection("navigation");
        ConfigurationSection filler = section.getConfigurationSection("filler");
        return new UiSection(id, slots, templates,
                placed(navigation, "previous", binder, "previous_page " + id),
                placed(navigation, "next", binder, "next_page " + id),
                filler == null ? null : readItem(filler, binder));
    }

    /**
     * Reads the ways a row of a section can be drawn.
     *
     * <p>Any key ending in {@code template} is one, named by what comes before
     * it: {@code selected_template} is {@code selected}. There are a hundred
     * and sixty-seven distinct names across the ecosystem and a plugin is free
     * to invent another, so they are read by shape rather than from a list.
     *
     * <p>{@code item_template} is the plain one and becomes
     * {@link UiSection#DEFAULT}. A section with several named templates and no
     * plain one uses the first as its default, because that is what the old
     * runtime did with a row nobody classified.
     */
    private static Map<String, UiItem> readTemplates(String id, ConfigurationSection section,
                                                     Binder binder) {
        Map<String, UiItem> templates = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            if (!key.endsWith("template") && !key.endsWith("templates")) {
                continue;
            }
            ConfigurationSection template = section.getConfigurationSection(key);
            if (template == null) {
                continue;
            }
            templates.put(templateName(key), readItem(template, binder));
        }
        if (!templates.isEmpty()) {
            templates.putIfAbsent(UiSection.DEFAULT, templates.values().iterator().next());
        }
        // A section with no template is not broken. ExyliaSandBox's kit room
        // lists the stacks it has stored, and no template could describe an
        // arbitrary saved item; those rows bring their own.
        return templates;
    }

    /** {@code selected_template} names the template {@code selected}. */
    private static String templateName(String key) {
        String name = key;
        for (String suffix : List.of("_template", "-template", "template")) {
            if (name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        name = name.replace('-', '_');
        while (name.endsWith("_")) {
            name = name.substring(0, name.length() - 1);
        }
        // "item_template" and a bare "template" are the ordinary row.
        return name.isEmpty() || name.equals("item") ? UiSection.DEFAULT : name;
    }

    /**
     * Reads a navigation arrow, and makes it page if the file did not say so.
     *
     * <p>An arrow under {@code navigation} is written with a slot and an icon
     * and nothing else — the old library paged by slot, so no file in the
     * ecosystem names an action here. Requiring one leaves every arrow ever
     * written inert: it draws, it is clickable, and nothing happens.
     *
     * <p>The fallback names the section, because a menu may have several lists
     * and each arrow belongs to exactly one of them. A file that does name its
     * own actions keeps them, so an arrow that does something else still can.
     *
     * @param fallback the action to bind when the file bound none
     */
    private static UiSection.Placed placed(ConfigurationSection navigation, String key,
                                           Binder binder, String fallback) {
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
        UiItem item = readItem(section, binder);
        if (!item.bindings().isEmpty()) {
            return new UiSection.Placed(slot, item);
        }
        ClickBindings paging;
        try {
            paging = new ClickBindings.Builder().add(fallback, binder.compiler()).build();
        } catch (RuntimeException unavailable) {
            // The paging actions are registered per plugin, and this is a
            // convenience rather than something the file asked for: an arrow
            // that cannot page is the arrow that would have been drawn anyway,
            // and is not worth refusing to load the menu over.
            return new UiSection.Placed(slot, item);
        }
        return new UiSection.Placed(slot,
                new UiItem(item.item(), paging, item.condition(), item.dependencies()));
    }

    private static UiAnimationSpec animation(ConfigurationSection section, String key,
                                             Problems problems) {
        if (!section.contains(key)) {
            return null;
        }
        UiAnimationSpec spec;
        if (section.isString(key)) {
            spec = UiAnimationSpec.of(section.getString(key, "none"));
        } else {
            ConfigurationSection animation = section.getConfigurationSection(key);
            if (animation == null) {
                return null;
            }
            // The old format wrote the open animation under "open".
            spec = animation.contains("open") && !animation.contains("type")
                    ? UiAnimationSpec.of(animation.getString("open", "none"))
                    : UiAnimationSpec.of(values(animation));
        }
        return checked(spec, problems);
    }

    /**
     * Reports an animation name nothing can draw.
     *
     * <p>Worth saying out loud: a misspelt name is indistinguishable from a
     * menu that simply appears, so without this an admin would be left
     * wondering why their animation does nothing. The menu still loads — an
     * animation is decoration.
     */
    private static UiAnimationSpec checked(UiAnimationSpec spec, Problems problems) {
        if (!OpenAnimation.isKnown(spec.type())) {
            problems.found("animation", "Unknown animation \"" + spec.type()
                    + "\"; the menu will appear at once. Known: "
                    + String.join(", ", new java.util.TreeSet<>(OpenAnimation.known())));
        }
        return spec;
    }

    private static Map<String, Object> values(ConfigurationSection section) {
        Map<String, Object> values = new HashMap<>();
        for (String key : section.getKeys(false)) {
            values.put(key, section.get(key));
        }
        return values;
    }
}
