package net.exylia.lib.region.internal;

import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.MaterialSet;
import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.text.Phrases;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

    private static final Map<PolicyKey<?>, Supplier<Look>> BUILT_IN = new LinkedHashMap<>();

    static {
        BUILT_IN.put(CommonRegionPolicies.PVP, () -> new Look("IRON_SWORD",
                Phrases.tr("{primary}&lPVP"), Phrases.tr("Players can hurt each other.")));
        BUILT_IN.put(CommonRegionPolicies.BUILD, () -> new Look("BRICKS",
                Phrases.tr("{primary}&lBUILD"), Phrases.tr("Players can place blocks.")));
        BUILT_IN.put(CommonRegionPolicies.BREAK, () -> new Look("IRON_PICKAXE",
                Phrases.tr("{primary}&lBREAK"), Phrases.tr("Players can break blocks.")));
        BUILT_IN.put(CommonRegionPolicies.INTERACT, () -> new Look("LEVER",
                Phrases.tr("{primary}&lINTERACT"), Phrases.tr("Blocks and entities can be used.")));
        BUILT_IN.put(CommonRegionPolicies.PLAYER_BUILD_ONLY, () -> new Look("SCAFFOLDING",
                Phrases.tr("{primary}&lPLAYER BLOCKS ONLY"), Phrases.tr("Only blocks a player placed can break.")));
        BUILT_IN.put(CommonRegionPolicies.ALLOWED_BLOCKS_ONLY, () -> new Look("CRAFTING_TABLE",
                Phrases.tr("{primary}&lALLOWED BLOCKS ONLY"), Phrases.tr("Only the allowed blocks can be placed.")));
        BUILT_IN.put(CommonRegionPolicies.BREAKABLE_BLOCKS_ONLY, () -> new Look("GOLDEN_PICKAXE",
                Phrases.tr("{primary}&lBREAKABLE BLOCKS ONLY"), Phrases.tr("Only the breakable blocks can break.")));
        BUILT_IN.put(CommonRegionPolicies.TEMPORARY_BLOCKS, () -> new Look("SAND",
                Phrases.tr("{primary}&lTEMPORARY BLOCKS"), Phrases.tr("Placed blocks vanish on their own.")));
        BUILT_IN.put(CommonRegionPolicies.RE_GIVE_BLOCKS, () -> new Look("BUNDLE",
                Phrases.tr("{primary}&lRETURN BLOCKS"), Phrases.tr("A vanished block goes back to its placer.")));
        BUILT_IN.put(CommonRegionPolicies.REGION_MEMBERS_ONLY, () -> new Look("NAME_TAG",
                Phrases.tr("{primary}&lMEMBERS ONLY"), Phrases.tr("Only region members can act inside.")));
        BUILT_IN.put(CommonRegionPolicies.ENTRY, () -> new Look("OAK_DOOR",
                Phrases.tr("{primary}&lENTRY"), Phrases.tr("Players can come in, teleports included.")));
        BUILT_IN.put(CommonRegionPolicies.EXIT, () -> new Look("IRON_DOOR",
                Phrases.tr("{primary}&lEXIT"), Phrases.tr("Players can leave, teleports included.")));
        BUILT_IN.put(CommonRegionPolicies.ITEM_DROP, () -> new Look("DROPPER",
                Phrases.tr("{primary}&lITEM DROP"), Phrases.tr("Players can drop items.")));
        BUILT_IN.put(CommonRegionPolicies.ITEM_PICKUP, () -> new Look("HOPPER",
                Phrases.tr("{primary}&lITEM PICKUP"), Phrases.tr("Players can pick items up.")));
        BUILT_IN.put(CommonRegionPolicies.FALL_DAMAGE, () -> new Look("FEATHER",
                Phrases.tr("{primary}&lFALL DAMAGE"), Phrases.tr("Falling hurts players.")));
        BUILT_IN.put(CommonRegionPolicies.TEMPORARY_BLOCKS_SECONDS, () -> new Look("CLOCK",
                Phrases.tr("{primary}&lBLOCK LIFETIME"), Phrases.tr("How long a temporary block lasts."),
                Phrases.tr("s ⌚")));
        BUILT_IN.put(CommonRegionPolicies.ALLOWED_BLOCKS, () -> new Look("WRITABLE_BOOK",
                Phrases.tr("{primary}&lALLOWED BLOCKS"), Phrases.tr("What can be placed when the list is on.")));
        BUILT_IN.put(CommonRegionPolicies.BREAKABLE_BLOCKS, () -> new Look("BOOK",
                Phrases.tr("{primary}&lBREAKABLE BLOCKS"), Phrases.tr("What can break when the list is on.")));
    }

    /** Every boolean common policy, in declaration order: the editor's default rows. */
    public static final List<PolicyKey<?>> BOOLEANS = booleans();

    private PolicyLooks() {
    }

    /** The built-in look of a common policy, or {@code null} for any other key. */
    public static @Nullable Look builtIn(@NotNull PolicyKey<?> key) {
        Supplier<Look> look = BUILT_IN.get(key);
        return look == null ? null : look.get();
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
                .orElseGet(() -> Phrases.tr("{muted}DEFAULT"));
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
        lore.add(Phrases.tr("{secondary}State:"));
        lore.add(Phrases.tr(" {letters_black}▎ {letters}Now {letters_black}» {0}",
                draft.explicit(key).map(value -> full(value, look)).orElseGet(() -> Phrases.tr("{muted}Default"))));
        lore.add(Phrases.tr(" {letters_black}▎ {letters}Default {letters_black}» {0}",
                draft.fallback(key).map(value -> full(value, look))
                        .orElseGet(() -> Phrases.tr("{muted}Set by the plugin"))));
        if (key.type() == MaterialSet.class) {
            MaterialSet listed = (MaterialSet) draft.explicit(key).orElse(null);
            if (listed != null && !listed.isEmpty()) {
                lore.add("");
                lore.add(Phrases.tr("{secondary}Materials:"));
                int shown = 0;
                for (org.bukkit.Material material : listed.materials()) {
                    if (shown++ == LISTED) {
                        lore.add(Phrases.tr(" {letters_black}▎ {muted}and {0} more", listed.materials().size() - LISTED));
                        break;
                    }
                    lore.add(" {letters_black}▎ {info}" + readable(material.name()));
                }
            }
        }
        lore.add("");
        String reason = draft.lockReason(key);
        if (reason != null) {
            lore.add(Phrases.tr("{secondary}Locked:"));
            lore.add(" {letters_black}▎ {muted}" + reason);
        } else {
            lore.add(key.type() == Boolean.class ? Phrases.tr("{warning}➥ Click to cycle")
                    : key.type() == Integer.class ? Phrases.tr("{warning}➥ Click to set")
                    : Phrases.tr("{warning}➥ Click to edit the list"));
            if (draft.explicit(key).isPresent()) {
                lore.add(Phrases.tr("{warning}➥ Right-click for default"));
            }
        }
        lore.add("");
        return lore;
    }

    /** A value inside the name's brackets. */
    private static String brief(Object value, Look look) {
        if (value instanceof Boolean allowed) {
            return allowed ? Phrases.tr("{success}ALLOW") : Phrases.tr("{error}DENY");
        }
        if (value instanceof MaterialSet set) {
            return "{info}" + set.materials().size();
        }
        return "{info}" + value + look.unit();
    }

    /** A value on a lore line. */
    private static String full(Object value, Look look) {
        if (value instanceof Boolean allowed) {
            return allowed ? Phrases.tr("{success}Allow") : Phrases.tr("{error}Deny");
        }
        if (value instanceof MaterialSet set) {
            int size = set.materials().size();
            return size == 0 ? Phrases.tr("{muted}No materials")
                    : size == 1 ? Phrases.tr("{info}{0} material", size) : Phrases.tr("{info}{0} materials", size);
        }
        return "{info}" + value + look.unit();
    }

    /** {@code GOLD_BLOCK} as {@code Gold block}. */
    static String readable(String name) {
        String words = name.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
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
