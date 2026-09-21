package net.exylia.lib.util.crate;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * What a crate can hand over: the only part a plugin writes.
 *
 * <pre>{@code
 * CrateCatalogue<Trim> catalogue = new CrateCatalogue<>() {
 *     public Collection<Trim> all() { return registry.all(); }
 *     public String id(Trim trim) { return trim.id(); }
 *     public String tier(Trim trim) { return trim.tier(); }
 *     public String name(Trim trim) { return trim.displayName(); }
 *     public String icon(Trim trim) { return trim.icon(); }
 *     public String description(Trim trim) { return trim.description(); }
 *     public ItemStack token(Trim trim, Player viewer) { return items.token(trim, viewer); }
 * };
 * }</pre>
 *
 * <p>Read afresh on every opening and every reel, so a plugin that reloads its
 * catalogue needs to tell the crate nothing. Called on the thread that owns the
 * player being served, and must not block.
 *
 * @param <T> the plugin's own reward type
 * @since 1.189.0
 */
public interface CrateCatalogue<T> {

    /** Every reward a crate may land on. An empty catalogue is an empty crate. */
    @NotNull Collection<T> all();

    /**
     * The reward's id, which is what is stored when it is unlocked.
     *
     * <p>Stored trimmed and lower-cased, and it must not contain a comma: the
     * unlocked ids of a player share one column.
     */
    @NotNull String id(@NotNull T reward);

    /**
     * The id of the rarity it belongs to, one of the keys of
     * {@link CrateSettings#tiers()}. One that no longer exists resolves to the
     * first rarity.
     */
    @NotNull String tier(@NotNull T reward);

    /** Its name as the server owner wrote it, formatting included. */
    @NotNull String name(@NotNull T reward);

    /** The material, head or other item source it is drawn as on the reels. */
    @NotNull String icon(@NotNull T reward);

    /**
     * The real item a reward is drawn as on the reels, for a reward a material
     * name cannot show: a banner carrying a shield's patterns, a trimmed or
     * dyed armour piece, a token with its own model.
     *
     * <p>It becomes what the reel templates' {@code material: "%reward_material%"}
     * resolves to, the same way a stored icon does: the item's look is kept —
     * patterns, trim, colour, model, glint — and its own name and lore are
     * dropped, so the template's name and lore are drawn on top of it. A
     * template that writes a literal material instead ignores it.
     *
     * <p>Asked once per reward per opening and kept for that opening only, so
     * a change in the catalogue shows on the next one.
     *
     * @param reward what is drawn
     * @param viewer who is watching the reels
     * @return the item, or {@code null} to draw {@link #icon(Object)} as before
     * @since 1.190.0
     */
    default @Nullable ItemStack icon(@NotNull T reward, @NotNull Player viewer) {
        return null;
    }

    /**
     * A second name the reel placeholders and the won and duplicate lines
     * answer to, for a plugin whose server owners already wrote their menus and
     * messages against its own crate.
     *
     * <p>With {@code "effect"}, every reel row carries {@code %effect_id%},
     * {@code %effect_name%}, {@code %effect_material%},
     * {@code %effect_description%}, {@code %effect_tier%},
     * {@code %effect_tier_id%} and {@code %effect_tier_color%} next to their
     * {@code %reward_*%} names, click actions included, and the two chat lines
     * fill {@code %effect%} as well as {@code %reward%}. Nothing is renamed:
     * both spellings resolve, so neither a customised file nor a fresh one
     * breaks.
     *
     * <p>Trimmed and lower-cased; blank, {@code reward}, or anything but
     * letters, digits and underscores means no alias.
     *
     * @return the alias, or {@code null} for none
     * @since 1.190.0
     */
    default @Nullable String placeholderPrefix() {
        return null;
    }

    /** One line about it, formatting included, or an empty string. */
    @NotNull String description(@NotNull T reward);

    /**
     * The item a crate hands over under {@link CrateReward#ITEM} or
     * {@link CrateReward#BOTH}.
     *
     * @param reward what was won
     * @param viewer who won it
     * @return the item, or {@code null} when this plugin has no token items,
     *         which makes those two modes behave as {@link CrateReward#UNLOCK}
     */
    @Nullable ItemStack token(@NotNull T reward, @NotNull Player viewer);

    /**
     * Whether a player owns a reward some other way than a crate, such as a
     * permission.
     *
     * <p>Only counted by the {@code %unlocked_count%} a crate screen shows. A
     * crate never asks it before handing a reward over: a reward a rank grants
     * is still unlocked for good when a key lands on it, so it outlives the
     * rank.
     *
     * @param player who
     * @param reward what
     * @return whether they own it without having unlocked it
     */
    default boolean ownsOtherwise(@NotNull Player player, @NotNull T reward) {
        return false;
    }
}
