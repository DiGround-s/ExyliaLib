package net.exylia.lib.region.internal;

import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.MaterialSet;
import net.exylia.lib.region.PolicyKey;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How each common policy looks on a policy editor, and which keys it can edit.
 *
 * <p>The icons are the ones ExyliaEvents, ExyliaPracticeCore and ExyliaFFA
 * already agreed on; the rest fill the gaps in the same spirit.
 */
@ApiStatus.Internal
public final class PolicyLooks {

    /**
     * One row's appearance.
     *
     * @param icon        a material, head string or {@code bytes:} snapshot
     * @param name        the item name, in Exylia text notation
     * @param description one short line of what it does
     * @param unit        drawn after an integer value, empty for none
     */
    public record Look(@NotNull String icon, @NotNull String name, @NotNull String description,
                       @NotNull String unit) {
        public Look(@NotNull String icon, @NotNull String name, @NotNull String description) {
            this(icon, name, description, "");
        }
    }

    private static final Map<PolicyKey<?>, Look> BUILT_IN = new LinkedHashMap<>();

    static {
        put(CommonRegionPolicies.PVP, "IRON_SWORD", "PVP", "Players can hurt each other.");
        put(CommonRegionPolicies.BUILD, "BRICKS", "BUILD", "Players can place blocks.");
        put(CommonRegionPolicies.BREAK, "IRON_PICKAXE", "BREAK", "Players can break blocks.");
        put(CommonRegionPolicies.INTERACT, "LEVER", "INTERACT", "Blocks and entities can be used.");
        put(CommonRegionPolicies.PLAYER_BUILD_ONLY, "SCAFFOLDING", "PLAYER BLOCKS ONLY",
                "Only blocks a player placed can break.");
        put(CommonRegionPolicies.ALLOWED_BLOCKS_ONLY, "CRAFTING_TABLE", "ALLOWED BLOCKS ONLY",
                "Only the allowed blocks can be placed.");
        put(CommonRegionPolicies.BREAKABLE_BLOCKS_ONLY, "GOLDEN_PICKAXE", "BREAKABLE BLOCKS ONLY",
                "Only the breakable blocks can break.");
        put(CommonRegionPolicies.TEMPORARY_BLOCKS, "SAND", "TEMPORARY BLOCKS",
                "Placed blocks vanish on their own.");
        put(CommonRegionPolicies.RE_GIVE_BLOCKS, "BUNDLE", "RETURN BLOCKS",
                "A vanished block goes back to its placer.");
        put(CommonRegionPolicies.REGION_MEMBERS_ONLY, "NAME_TAG", "MEMBERS ONLY",
                "Only region members can act inside.");
        put(CommonRegionPolicies.ENTRY, "OAK_DOOR", "ENTRY", "Players can come in, teleports included.");
        put(CommonRegionPolicies.EXIT, "IRON_DOOR", "EXIT", "Players can leave, teleports included.");
        put(CommonRegionPolicies.ITEM_DROP, "DROPPER", "ITEM DROP", "Players can drop items.");
        put(CommonRegionPolicies.ITEM_PICKUP, "HOPPER", "ITEM PICKUP", "Players can pick items up.");
        put(CommonRegionPolicies.FALL_DAMAGE, "FEATHER", "FALL DAMAGE", "Falling hurts players.");
        BUILT_IN.put(CommonRegionPolicies.TEMPORARY_BLOCKS_SECONDS, new Look("CLOCK",
                "{primary}&lBLOCK LIFETIME", "How long a temporary block lasts.", "s ⌚"));
        put(CommonRegionPolicies.ALLOWED_BLOCKS, "WRITABLE_BOOK", "ALLOWED BLOCKS",
                "What can be placed when the list is on.");
        put(CommonRegionPolicies.BREAKABLE_BLOCKS, "BOOK", "BREAKABLE BLOCKS",
                "What can break when the list is on.");
    }

    /** Every boolean common policy, in declaration order: the editor's default rows. */
    public static final List<PolicyKey<?>> BOOLEANS = booleans();

    private PolicyLooks() {
    }

    /** The built-in look of a common policy, or {@code null} for any other key. */
    public static @Nullable Look builtIn(@NotNull PolicyKey<?> key) {
        return BUILT_IN.get(key);
    }

    /** Whether the editor has a row type for a key's value. */
    public static boolean editable(@NotNull PolicyKey<?> key) {
        Class<?> type = key.type();
        return type == Boolean.class || type == Integer.class || type == MaterialSet.class;
    }

    /** How many materials a list row names before it says how many more. */
    static final int LISTED = 5;

    /**
     * The row's item name: its look's name and where it stands, in brackets.
     *
     * @param key  the row
     * @param look how it looks
     * @param draft the working copy
     * @return the name, in Exylia text notation
     */
    public static @NotNull String name(@NotNull PolicyKey<?> key, @NotNull Look look,
                                       @NotNull PolicyDraft draft) {
        String value = draft.explicit(key).map(explicit -> brief(explicit, look))
                .orElse("{muted}DEFAULT");
        return look.name() + " &8[" + value + "&8]";
    }

    /**
     * The row's lore: what it does, where it stands, what Default means, and
     * what a click does — or why it cannot.
     *
     * @param key   the row
     * @param look  how it looks
     * @param draft the working copy
     * @return the lines, in Exylia text notation
     */
    public static @NotNull List<String> lore(@NotNull PolicyKey<?> key, @NotNull Look look,
                                             @NotNull PolicyDraft draft) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(" {letters_black}▎ {letters}" + look.description());
        lore.add("");
        lore.add("{secondary}State:");
        lore.add(" {letters_black}▎ {letters}Now {letters_black}» "
                + draft.explicit(key).map(value -> full(value, look)).orElse("{muted}Default"));
        lore.add(" {letters_black}▎ {letters}Default {letters_black}» "
                + draft.fallback(key).map(value -> full(value, look)).orElse("{muted}Set by the plugin"));
        if (key.type() == MaterialSet.class) {
            MaterialSet listed = (MaterialSet) draft.explicit(key).orElse(null);
            if (listed != null && !listed.isEmpty()) {
                lore.add("");
                lore.add("{secondary}Materials:");
                int shown = 0;
                for (org.bukkit.Material material : listed.materials()) {
                    if (shown++ == LISTED) {
                        lore.add(" {letters_black}▎ {muted}and " + (listed.materials().size() - LISTED) + " more");
                        break;
                    }
                    lore.add(" {letters_black}▎ {info}" + readable(material.name()));
                }
            }
        }
        lore.add("");
        String reason = draft.lockReason(key);
        if (reason != null) {
            lore.add("{secondary}Locked:");
            lore.add(" {letters_black}▎ {muted}" + reason);
        } else {
            lore.add("{warning}➥ " + (key.type() == Boolean.class ? "Click to cycle"
                    : key.type() == Integer.class ? "Click to set" : "Click to edit the list"));
            if (draft.explicit(key).isPresent()) {
                lore.add("{warning}➥ Right-click for default");
            }
        }
        lore.add("");
        return lore;
    }

    /** A value inside the name's brackets. */
    private static String brief(Object value, Look look) {
        if (value instanceof Boolean allowed) {
            return allowed ? "{success}ALLOW" : "{error}DENY";
        }
        if (value instanceof MaterialSet set) {
            return "{info}" + set.materials().size();
        }
        return "{info}" + value + look.unit();
    }

    /** A value on a lore line. */
    private static String full(Object value, Look look) {
        if (value instanceof Boolean allowed) {
            return allowed ? "{success}Allow" : "{error}Deny";
        }
        if (value instanceof MaterialSet set) {
            int size = set.materials().size();
            return size == 0 ? "{muted}No materials" : "{info}" + size + (size == 1 ? " material" : " materials");
        }
        return "{info}" + value + look.unit();
    }

    /** {@code GOLD_BLOCK} as {@code Gold block}. */
    static String readable(String name) {
        String words = name.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static void put(PolicyKey<?> key, String icon, String name, String description) {
        BUILT_IN.put(key, new Look(icon, "{primary}&l" + name, description));
    }

    private static List<PolicyKey<?>> booleans() {
        List<PolicyKey<?>> keys = new ArrayList<>();
        for (PolicyKey<?> key : BUILT_IN.keySet()) {
            if (key.type() == Boolean.class) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }
}
