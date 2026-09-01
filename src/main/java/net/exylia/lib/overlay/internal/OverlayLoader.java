package net.exylia.lib.overlay.internal;

import net.exylia.lib.action.ActionTemplate;
import net.exylia.lib.overlay.OverlayDefinition;
import net.exylia.lib.overlay.OverlayLock;
import net.exylia.lib.overlay.OverlaySlots;
import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.ui.UiRefresh;
import net.exylia.lib.ui.UiSounds;
import net.exylia.lib.ui.internal.MenuLoader;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads an overlay file into a compiled definition.
 *
 * <p>Items are read by {@link MenuLoader}, so an overlay's buttons are written
 * exactly the way a menu's are: same {@code material}, same {@code actions},
 * same {@code condition}. Only the slot differs, because an overlay's slots
 * are places in a player's inventory and can be named.
 */
public final class OverlayLoader {

    private OverlayLoader() {
    }

    /**
     * Compiles an overlay.
     *
     * <p>A structural mistake — a slot outside the player inventory — throws,
     * because it means the file does not describe an overlay and guessing
     * would hide the mistake. A mistyped action becomes a dead button and a
     * line in the console, exactly as in a menu.
     *
     * @param id       the id to give it
     * @param config   the file's root section
     * @param compiler how to compile an action string
     * @param defaults the sounds to fall back to
     * @param problems where to report bad parts
     * @return the compiled overlay
     */
    public static OverlayDefinition load(String id, ConfigurationSection config,
                                         Function<String, ActionTemplate> compiler,
                                         UiSounds defaults, MenuLoader.Problems problems) {
        Map<Integer, UiItem> items = new LinkedHashMap<>();
        readItems(config.getConfigurationSection("items"), compiler, problems, items);
        return new OverlayDefinition(
                id,
                items,
                UiRefresh.of(values(config.getConfigurationSection("refresh"))),
                OverlayLock.byName(config.getString("lock")),
                // Both spellings, because every other key in the ecosystem
                // accepts both and one that did not would be the odd one out.
                config.getBoolean("pickup", config.getBoolean("pick-up", true)),
                config.getBoolean("hide_rest", config.getBoolean("hide-rest", false)),
                UiSounds.of(values(config.getConfigurationSection("sounds")), defaults),
                emptyHand(config, compiler, problems));
    }

    /**
     * What a slot the overlay owns but draws nothing in does.
     *
     * <p>Written as an item's {@code actions} and {@code commands} are, minus
     * the item: there is nothing to draw, which is the point of it.
     */
    private static ClickBindings emptyHand(ConfigurationSection config,
                                           Function<String, ActionTemplate> compiler,
                                           MenuLoader.Problems problems) {
        ConfigurationSection section = config.getConfigurationSection("empty_hand");
        if (section == null) {
            section = config.getConfigurationSection("empty-hand");
        }
        if (section == null) {
            return ClickBindings.none();
        }
        ClickBindings.Builder bindings = new ClickBindings.Builder();
        for (String line : section.getStringList("actions")) {
            bindings.add(line, compiler);
        }
        for (String line : section.getStringList("commands")) {
            try {
                bindings.addCommand(line);
            } catch (IllegalArgumentException empty) {
                problems.found("empty_hand command \"" + line + "\"", empty.getMessage());
            }
        }
        return bindings.build();
    }

    private static void readItems(ConfigurationSection section,
                                  Function<String, ActionTemplate> compiler,
                                  MenuLoader.Problems problems,
                                  Map<Integer, UiItem> into) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection itemSection = section.getConfigurationSection(key);
            if (itemSection == null) {
                continue;
            }
            UiItem item = MenuLoader.item(itemSection, compiler, problems);
            for (int slot : slotsOf(itemSection, key)) {
                if (!OverlaySlots.isValid(slot)) {
                    throw new IllegalArgumentException("Item \"" + key + "\" uses slot " + slot
                            + ", outside the player inventory (0-"
                            + (OverlaySlots.SIZE - 1) + ")");
                }
                into.put(slot, item);
            }
        }
    }

    /** Where one item goes, in any of the forms configuration uses. */
    private static List<Integer> slotsOf(ConfigurationSection section, String key) {
        boolean hasSlot = section.contains("slot");
        boolean hasSlots = section.contains("slots");
        if (hasSlot && hasSlots) {
            throw new IllegalArgumentException(
                    "Item \"" + key + "\" declares both slot and slots; pick one");
        }
        if (!hasSlot && !hasSlots) {
            throw new IllegalArgumentException("Item \"" + key + "\" has no slot");
        }
        return parse(section, hasSlot ? "slot" : "slots");
    }

    private static List<Integer> parse(ConfigurationSection section, String key) {
        if (section.isInt(key)) {
            return List.of(section.getInt(key));
        }
        if (section.isString(key)) {
            return OverlaySlots.parse(section.getString(key, ""));
        }
        if (section.isList(key)) {
            List<Integer> all = new ArrayList<>();
            for (Object entry : section.getList(key, List.of())) {
                if (entry instanceof Number number) {
                    all.add(number.intValue());
                } else if (entry != null) {
                    all.addAll(OverlaySlots.parse(String.valueOf(entry)));
                }
            }
            return List.copyOf(all);
        }
        return List.of();
    }

    private static Map<String, Object> values(ConfigurationSection section) {
        return section == null ? Map.of() : section.getValues(false);
    }
}
