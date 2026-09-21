package net.exylia.lib.util.crate;

import net.exylia.lib.config.Comment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One rarity, and how often a crate lands on it.
 *
 * <p>The key it is written under in {@link CrateSettings#tiers()} is its id, and
 * that id is what {@link CrateCatalogue#tier} answers for each reward. Nothing
 * is hard-coded: a server that deletes {@code legendary} or invents
 * {@code mythic} gets exactly what it wrote, and a reward naming a rarity that
 * no longer exists falls back to the first one.
 *
 * @param name     what menus and messages call it
 * @param color    the colour its name and its rewards are written in, as a palette token
 * @param chance   how often a crate lands on it, as a weight relative to the others
 * @param priority where it sits in menus; lower comes first
 * @since 1.189.0
 */
public record CrateTier(
        @Comment("What menus and messages call it.")
        String name,

        @Comment("The colour its name and its rewards are written in.")
        String color,

        @Comment("How often a crate lands on it, relative to the other rarities.")
        @Comment("These are weights, not percentages: the odds are worked out from them,")
        @Comment("so deleting a rarity re-spreads its odds instead of leaving a gap.")
        double chance,

        @Comment("Where it sits in menus; lower comes first.")
        int priority) {

    public CrateTier() {
        this("Common", "{muted}", 60.0, 1);
    }

    public CrateTier {
        name = name == null ? "" : name;
        color = color == null ? "" : color;
    }

    /**
     * The four rarities a crate ships with: common 60, rare 25, epic 12 and
     * legendary 3.
     *
     * @return them, in that order
     */
    public static Map<String, CrateTier> defaults() {
        Map<String, CrateTier> tiers = new LinkedHashMap<>();
        tiers.put("common", new CrateTier("Common", "{muted}", 60.0, 1));
        tiers.put("rare", new CrateTier("Rare", "{info}", 25.0, 2));
        tiers.put("epic", new CrateTier("Epic", "{accent}", 12.0, 3));
        tiers.put("legendary", new CrateTier("Legendary", "{highlight}", 3.0, 4));
        return Collections.unmodifiableMap(tiers);
    }
}
