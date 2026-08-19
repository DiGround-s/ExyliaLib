package net.exylia.lib.util.reward;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * What a reward actually gives.
 *
 * <p>The first three names are the ones ExyliaCommons wrote into databases and
 * they are fixed forever: {@code COMMAND}, {@code ITEM} and {@code MESSAGE} are
 * stored verbatim as the {@code type} field of every persisted reward row. The
 * rest are new and only ever appear in rows written by this library.
 *
 * <h2>Why a new type is safe</h2>
 * A plugin still on the old module reading a row that names {@code ECONOMY}
 * gets {@code null} from Gson and skips that one reward rather than losing the
 * whole list. That is the reason {@link #parse} reports an unknown name instead
 * of throwing: a server mid-migration must lose at most the reward it cannot
 * understand.
 *
 * @since 1.34.0
 */
public enum RewardType {

    /** Runs a command. The console runs it unless the reward says otherwise. */
    COMMAND,

    /** Gives an item. */
    ITEM,

    /** Sends a message. */
    MESSAGE,

    /**
     * Deposits money through the economy module.
     *
     * @since 1.34.0
     */
    ECONOMY,

    /**
     * Grants experience.
     *
     * @since 1.34.0
     */
    EXPERIENCE,

    /**
     * Applies a potion effect, written the way {@link net.exylia.lib.util.Effects}
     * reads it: {@code SPEED:1:300}.
     *
     * @since 1.34.0
     */
    POTION;

    /**
     * Reads a stored type name.
     *
     * <p>Case-insensitive, because a hand-edited config is not a database row.
     *
     * @param stored the name as written
     * @return the type, or {@code null} if no such type exists
     */
    public static @Nullable RewardType parse(@Nullable String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String name = stored.trim().toUpperCase(Locale.ROOT);
        for (RewardType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Whether this type existed in ExyliaCommons.
     *
     * <p>Only these three can appear in a row an unmigrated plugin has to read.
     *
     * @return whether the old module knew this type
     */
    public boolean isLegacy() {
        return this == COMMAND || this == ITEM || this == MESSAGE;
    }

    /** The material a menu draws for this type when the reward names no icon. */
    @NotNull String defaultIcon() {
        return switch (this) {
            case COMMAND -> "COMMAND_BLOCK";
            case ITEM -> "CHEST";
            case MESSAGE -> "PAPER";
            case ECONOMY -> "GOLD_INGOT";
            case EXPERIENCE -> "EXPERIENCE_BOTTLE";
            case POTION -> "POTION";
        };
    }
}
