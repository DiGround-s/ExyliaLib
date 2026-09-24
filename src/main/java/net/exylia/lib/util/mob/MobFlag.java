package net.exylia.lib.util.mob;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * A yes-or-no switch on a {@link MobTemplate}.
 *
 * <p>Stored by name, so a flag a newer library adds is skipped and reported by
 * an older one rather than breaking the template it sits in.
 *
 * @since 1.192.0
 */
public enum MobFlag {

    /** Drawn with the glowing outline. */
    GLOWING("Glows through walls"),
    /** Spawned as a baby, where the type has one. */
    BABY("Spawns as a baby"),
    /** Makes no sound. */
    SILENT("Makes no sound"),
    /** Never catches fire and takes no fire or lava damage. */
    FIRE_IMMUNE("Immune to fire and lava"),
    /** Drops none of the loot the type drops in vanilla. */
    NO_VANILLA_DROPS("Drops no vanilla loot"),
    /** Drops none of the experience the type drops in vanilla. */
    NO_VANILLA_EXP("Drops no vanilla experience"),
    /** Never picks up items from the ground. */
    NO_ITEM_PICKUP("Never picks up items"),
    /** Does not burn in daylight. */
    NO_SUN_BURN("Does not burn in daylight"),
    /**
     * Goes after the nearest player within its follow range, even when the type
     * is neutral. A type with no attack of its own — a cow, a villager — follows
     * but cannot hurt anybody.
     */
    AGGRESSIVE("Hunts the nearest player");

    private final String description;

    MobFlag(String description) {
        this.description = description;
    }

    /** What the flag does, for an editor row. */
    public @NotNull String description() {
        return description;
    }

    /** The flag's name as a person reads it, such as {@code no sun burn}. */
    public @NotNull String readable() {
        return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
