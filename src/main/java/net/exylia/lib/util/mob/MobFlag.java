package net.exylia.lib.util.mob;

import net.exylia.lib.text.Phrases;
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
    GLOWING,
    /** Spawned as a baby, where the type has one. */
    BABY,
    /** Makes no sound. */
    SILENT,
    /** Never catches fire and takes no fire or lava damage. */
    FIRE_IMMUNE,
    /** Drops none of the loot the type drops in vanilla. */
    NO_VANILLA_DROPS,
    /** Drops none of the experience the type drops in vanilla. */
    NO_VANILLA_EXP,
    /** Never picks up items from the ground. */
    NO_ITEM_PICKUP,
    /** Does not burn in daylight. */
    NO_SUN_BURN,
    /**
     * Goes after the nearest player within its follow range, even when the type
     * is neutral. A type with no attack of its own — a cow, a villager — follows
     * but cannot hurt anybody.
     */
    AGGRESSIVE,
    /**
     * Always on the move: whenever it has no target and no path, it runs to a
     * random spot within its {@link MobBehaviour#roam()}, or ten blocks when it
     * has none. Checked every other tick.
     *
     * @since 1.195.0
     */
    WANDERS,
    /**
     * Nobody rides, leashes, feeds, breeds, opens or right-clicks it.
     *
     * @since 1.195.0
     */
    NO_INTERACT,
    /**
     * Never targets anything, so it never attacks or spits. Wins over
     * {@link #AGGRESSIVE}. Its skills still aim at the nearest player.
     *
     * @since 1.195.0
     */
    PASSIVE;

    /** What the flag does, for an editor row. */
    public @NotNull String description() {
        return switch (this) {
            case GLOWING -> Phrases.tr("Glows through walls");
            case BABY -> Phrases.tr("Spawns as a baby");
            case SILENT -> Phrases.tr("Makes no sound");
            case FIRE_IMMUNE -> Phrases.tr("Immune to fire and lava");
            case NO_VANILLA_DROPS -> Phrases.tr("Drops no vanilla loot");
            case NO_VANILLA_EXP -> Phrases.tr("Drops no vanilla experience");
            case NO_ITEM_PICKUP -> Phrases.tr("Never picks up items");
            case NO_SUN_BURN -> Phrases.tr("Does not burn in daylight");
            case AGGRESSIVE -> Phrases.tr("Hunts the nearest player");
            case WANDERS -> Phrases.tr("Always runs around");
            case NO_INTERACT -> Phrases.tr("Cannot be ridden, leashed, fed or opened");
            case PASSIVE -> Phrases.tr("Never targets anything");
        };
    }

    /** The flag's name as a person reads it, such as {@code no sun burn}. */
    public @NotNull String readable() {
        return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
