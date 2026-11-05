package net.exylia.lib.region.internal;

import net.exylia.lib.region.CommonRegionPolicies;
import net.exylia.lib.region.MaterialSet;
import net.exylia.lib.region.PolicyKey;
import net.exylia.lib.text.Lore;
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
                flag(Phrases.tr("PVP")), Phrases.tr("Players can hurt each other.")));
        BUILT_IN.put(CommonRegionPolicies.BUILD, () -> new Look("BRICKS",
                flag(Phrases.tr("Build")), Phrases.tr("Players can place blocks.")));
        BUILT_IN.put(CommonRegionPolicies.BREAK, () -> new Look("IRON_PICKAXE",
                flag(Phrases.tr("Break")), Phrases.tr("Players can break blocks.")));
        BUILT_IN.put(CommonRegionPolicies.INTERACT, () -> new Look("LEVER",
                flag(Phrases.tr("Interact")), Phrases.tr("Blocks and entities can be used.")));
        BUILT_IN.put(CommonRegionPolicies.PLAYER_BUILD_ONLY, () -> new Look("SCAFFOLDING",
                flag(Phrases.tr("Player blocks only")), Phrases.tr("Only blocks a player placed can break.")));
        BUILT_IN.put(CommonRegionPolicies.ALLOWED_BLOCKS_ONLY, () -> new Look("CRAFTING_TABLE",
                flag(Phrases.tr("Allowed blocks only")), Phrases.tr("Only the allowed blocks can be placed.")));
        BUILT_IN.put(CommonRegionPolicies.BREAKABLE_BLOCKS_ONLY, () -> new Look("GOLDEN_PICKAXE",
                flag(Phrases.tr("Breakable blocks only")), Phrases.tr("Only the breakable blocks can break.")));
        BUILT_IN.put(CommonRegionPolicies.TEMPORARY_BLOCKS, () -> new Look("SAND",
                flag(Phrases.tr("Temporary blocks")), Phrases.tr("Placed blocks vanish on their own.")));
        BUILT_IN.put(CommonRegionPolicies.RE_GIVE_BLOCKS, () -> new Look("BUNDLE",
                flag(Phrases.tr("Return blocks")), Phrases.tr("A vanished block goes back to its placer.")));
        BUILT_IN.put(CommonRegionPolicies.REGION_MEMBERS_ONLY, () -> new Look("NAME_TAG",
                flag(Phrases.tr("Members only")), Phrases.tr("Only region members can act inside.")));
        BUILT_IN.put(CommonRegionPolicies.ENTRY, () -> new Look("OAK_DOOR",
                flag(Phrases.tr("Entry")), Phrases.tr("Players can come in, teleports included.")));
        BUILT_IN.put(CommonRegionPolicies.EXIT, () -> new Look("IRON_DOOR",
                flag(Phrases.tr("Exit")), Phrases.tr("Players can leave, teleports included.")));
        BUILT_IN.put(CommonRegionPolicies.ITEM_DROP, () -> new Look("DROPPER",
                flag(Phrases.tr("Item drop")), Phrases.tr("Players can drop items.")));
        BUILT_IN.put(CommonRegionPolicies.ITEM_PICKUP, () -> new Look("HOPPER",
                flag(Phrases.tr("Item pickup")), Phrases.tr("Players can pick items up.")));
        BUILT_IN.put(CommonRegionPolicies.FALL_DAMAGE, () -> new Look("FEATHER",
                flag(Phrases.tr("Fall damage")), Phrases.tr("Falling hurts players.")));
        BUILT_IN.put(CommonRegionPolicies.TEMPORARY_BLOCKS_SECONDS, () -> new Look("CLOCK",
                flag(Phrases.tr("Block lifetime")), Phrases.tr("How long a temporary block lasts."),
                Phrases.tr("s ⌚")));
        BUILT_IN.put(CommonRegionPolicies.BUILD_MAX_Y, () -> new Look("LADDER",
                flag(Phrases.tr("Build height")),
                Phrases.tr("Highest Y a block can be placed at. 0 means no limit.")));
        BUILT_IN.put(CommonRegionPolicies.ALLOWED_BLOCKS, () -> new Look("WRITABLE_BOOK",
                flag(Phrases.tr("Allowed blocks")), Phrases.tr("What can be placed when the list is on.")));
        BUILT_IN.put(CommonRegionPolicies.BREAKABLE_BLOCKS, () -> new Look("BOOK",
                flag(Phrases.tr("Breakable blocks")), Phrases.tr("What can break when the list is on.")));
    }

    /** A built-in flag's card name. */
    private static String flag(String subject) {
        return Lore.name(Lore.Tone.PRIMARY, Phrases.tr("Flag"), subject);
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
        Lore lore = Lore.of(Lore.Tone.PRIMARY).subtitle(Phrases.tr("Region flag"))
                .section(Phrases.tr("Information"));
        for (String line : net.exylia.lib.text.Lines.split(net.exylia.lib.text.Lines.wrap(look.description(), 34))) {
            lore.text(line);
        }
        lore.section("✦", Phrases.tr("Status"));
        lore.line(Phrases.tr("  {letters_black}· {info}{icon:redstone} {letters}Now{letters_black}: {0}",
                draft.explicit(key).map(value -> full(value, look)).orElseGet(() -> Phrases.tr("{muted}Default"))));
        lore.line(Phrases.tr("  {letters_black}· {info}{icon:book} {letters}Default{letters_black}: {0}",
                draft.fallback(key).map(value -> full(value, look))
                        .orElseGet(() -> Phrases.tr("{muted}Set by the plugin"))));
        if (key.type() == MaterialSet.class) {
            MaterialSet listed = (MaterialSet) draft.explicit(key).orElse(null);
            if (listed != null && !listed.isEmpty()) {
                lore.section("✦", Phrases.tr("Materials"));
                int shown = 0;
                for (org.bukkit.Material material : listed.materials()) {
                    if (shown++ == LISTED) {
                        lore.line(Phrases.tr(" {muted}and {0} more", listed.materials().size() - LISTED));
                        break;
                    }
                    lore.line(" {info}" + readable(material.name()));
                }
            }
        }
        String reason = draft.lockReason(key);
        if (reason != null) {
            lore.section("✦", Phrases.tr("Locked"));
            lore.line(" {muted}" + reason);
        } else {
            lore.action(key.type() == Boolean.class ? Phrases.tr("Click to cycle")
                    : key.type() == Integer.class ? Phrases.tr("Click to set")
                    : Phrases.tr("Click to edit the list"));
            if (draft.explicit(key).isPresent()) {
                lore.action(Phrases.tr("Right-click for default"));
            }
        }
        return lore.lines();
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
