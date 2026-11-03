package net.exylia.lib.cosmetic;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Menu;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * One line of a cosmetic plugin's defaults: who gets which cosmetic while they
 * have not picked one themselves.
 *
 * <pre>{@code
 * // In the plugin's settings record:
 * @Comment("What a player who never picked an effect plays. The first line they qualify for wins.")
 * List<CosmeticDefault> defaults
 *
 * // Where the plugin decides what to draw:
 * String chosen = data.killEffect();
 * if (CosmeticDefault.isNone(chosen)) return null;                    // they turned it off
 * if (chosen != null && usable(player, chosen)) return chosen;         // their own choice wins
 * return CosmeticDefault.resolve(player, settings().defaults(), registry::exists);
 * }</pre>
 *
 * <pre>{@code
 * defaults:
 *   - permission: group.vip
 *     cosmetic: lightning
 *   - permission: ''        # everyone else
 *     cosmetic: blood
 * }</pre>
 *
 * <h2>Why rules and not a permission per cosmetic</h2>
 * Ranks inherit: a VIP also holds every node the default group holds, so two
 * "default" permissions would both answer yes and nothing would say which one
 * wins. A list read top to bottom does: the first line the player qualifies
 * for is theirs.
 *
 * <h2>Read live, never stored</h2>
 * Nothing is written to the player's row. A rank that runs out, a rank bought
 * or a line changed in the file shows on the next draw, and the moment the
 * player picks something of their own the default stops mattering.
 *
 * <h2>Turning it off</h2>
 * A player who unequips stores {@link #NONE}, not an empty column: empty means
 * "never chose", which is exactly when the default applies. Without the
 * sentinel nobody given a default could ever go without one.
 *
 * <h2>Not an unlock</h2>
 * The owner gives the cosmetic on purpose, so it plays whether or not the
 * player owns it, and it does not become theirs: it is not added to their
 * unlocks, and once they pick something else they get it back only as a
 * default.
 *
 * @param permission the node the player needs, or empty for everyone
 * @param cosmetic   the plugin's id for the cosmetic (the plugin may also accept
 *                   its own words, such as {@code random})
 * @since 1.468.0
 */
@Menu.Label("Default")
@Menu.Icon("NAME_TAG")
public record CosmeticDefault(
        @Menu.Label("Permission") @Menu.Icon("TRIPWIRE_HOOK")
        @Menu.About({"Who gets this line, such as {highlight}group.vip{letters}.", "Empty gives it to everyone."})
        @Comment("Who gets it: a permission node such as group.vip. Leave it empty for everyone.")
        String permission,

        @Menu.Label("Cosmetic") @Menu.Icon("NETHER_STAR")
        @Menu.About({"The id this line gives, as written", "in the plugin's catalogue."})
        @Comment("The id of what they get, as written in this plugin's catalogue.")
        String cosmetic) {

    /**
     * What a player stores when they turn the cosmetic off.
     *
     * <p>Kept in the same column as a cosmetic id. No catalogue ships an id by
     * this name, and a plugin whose catalogue could should refuse it on load.
     */
    public static final String NONE = "none";

    public CosmeticDefault() {
        this("", "");
    }

    /** Whether a stored choice is the player turning the cosmetic off. */
    public static boolean isNone(@Nullable String chosen) {
        return NONE.equalsIgnoreCase(chosen == null ? null : chosen.trim());
    }

    /** Whether this line is for that player. */
    public boolean appliesTo(@NotNull Player player) {
        return permission == null || permission.isBlank() || player.hasPermission(permission.trim());
    }

    /**
     * The cosmetic a player who has not chosen one gets, or {@code null}.
     *
     * <p>Lines are read in order. A line whose cosmetic the plugin does not
     * know (removed from the catalogue, a typo) is skipped rather than ending
     * the search, so the next line still applies. Asks permissions, so call it
     * where the plugin may ask them: any thread, as Bukkit allows.
     *
     * @param player   the player whose cosmetic is being drawn
     * @param defaults the plugin's lines, top to bottom; {@code null} reads as none
     * @param usable   whether the plugin can play an id ({@code registry::exists})
     * @return the trimmed id of the first line that applies, or {@code null}
     */
    public static @Nullable String resolve(@NotNull Player player,
                                           @Nullable List<CosmeticDefault> defaults,
                                           @NotNull Predicate<String> usable) {
        if (defaults == null) return null;
        for (CosmeticDefault line : defaults) {
            if (line == null || line.cosmetic() == null || line.cosmetic().isBlank()) continue;
            String id = line.cosmetic().trim();
            if (isNone(id)) {
                // An owner who writes "none" means this group gets nothing,
                // not "keep looking": stop here.
                if (line.appliesTo(player)) return null;
                continue;
            }
            if (line.appliesTo(player) && usable.test(id)) return id;
        }
        return null;
    }
}
