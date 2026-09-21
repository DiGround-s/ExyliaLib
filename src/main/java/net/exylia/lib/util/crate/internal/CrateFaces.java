package net.exylia.lib.util.crate.internal;

import net.exylia.lib.item.Source;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.util.crate.CrateCatalogue;
import net.exylia.lib.util.crate.CrateTier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Everything a reel template can say about one reward, for one opening.
 *
 * <p>Built once per opening rather than per cell: the rarities are read once,
 * and a reward drawn as a real item is encoded once however many times it goes
 * past. The encoded icons are kept here and nowhere else, so they are bounded
 * by the catalogue and go with the opening.
 *
 * @param <T> the plugin's reward type
 */
public final class CrateFaces<T> {

    /** The names every face carries, without the prefix. */
    private static final String[] NAMES = {
            "id", "name", "material", "description", "tier_id", "tier", "tier_color"};

    private static final Pattern ALIAS = Pattern.compile("[a-z0-9_]+");

    private final CrateCatalogue<T> catalogue;
    private final TierTable tiers;
    private final @Nullable Player viewer;
    private final Function<ItemStack, String> encode;
    private final @Nullable String alias;
    /** A reward's id to what its material resolves to, for this opening. */
    private final Map<String, String> materials = new HashMap<>();

    /**
     * @param viewer who watches, asked of {@link CrateCatalogue#icon(Object, Player)};
     *               {@code null} draws every reward from its icon string
     * @param encode how a real item becomes a material value
     */
    public CrateFaces(@NotNull CrateCatalogue<T> catalogue, @NotNull TierTable tiers,
                      @Nullable Player viewer, @NotNull Function<ItemStack, String> encode) {
        this.catalogue = catalogue;
        this.tiers = tiers;
        this.viewer = viewer;
        this.encode = encode;
        this.alias = alias(catalogue);
    }

    /** The same, encoding an item the way a stored icon is: its look kept, its name and lore dropped. */
    public CrateFaces(@NotNull CrateCatalogue<T> catalogue, @NotNull TierTable tiers, @Nullable Player viewer) {
        this(catalogue, tiers, viewer, stack -> Source.of(stack).raw());
    }

    /**
     * The catalogue's placeholder alias, normalised, or {@code null} when it
     * has none or names something that could not be a placeholder.
     */
    public static @Nullable String alias(@NotNull CrateCatalogue<?> catalogue) {
        String written = catalogue.placeholderPrefix();
        if (written == null) return null;
        String alias = written.trim().toLowerCase(Locale.ROOT);
        if (alias.isEmpty() || alias.equals("reward") || !ALIAS.matcher(alias).matches()) return null;
        return alias;
    }

    /** One reward's row, before the template is chosen. */
    public @NotNull UiEntry.Builder face(@NotNull T reward) {
        String id = TierTable.normalise(catalogue.id(reward));
        String tierId = tiers.resolveId(catalogue.tier(reward));
        CrateTier tier = tiers.get(tierId);
        String[] values = {
                id,
                catalogue.name(reward),
                materials.computeIfAbsent(id, ignored -> material(reward)),
                catalogue.description(reward),
                tierId,
                tier.color() + tier.name(),
                tier.color()};

        UiEntry.Builder entry = UiEntry.of(reward);
        put(entry, "reward", values);
        // Row values rather than a rewrite of the file: they reach the
        // template and the click actions alike, which is where a customised
        // menu wrote them.
        if (alias != null) put(entry, alias, values);
        return entry;
    }

    private static void put(UiEntry.Builder entry, String prefix, String[] values) {
        for (int i = 0; i < NAMES.length; i++) {
            String key = prefix + '_' + NAMES[i];
            String name = NAMES[i];
            // The id, the material and the tier id are data; the rest is the
            // owner's own formatting.
            if (name.equals("id") || name.equals("material") || name.equals("tier_id")) {
                entry.with(key, values[i]);
            } else {
                entry.withFormatted(key, values[i]);
            }
        }
    }

    private String material(T reward) {
        ItemStack item = viewer == null ? null : catalogue.icon(reward, viewer);
        return item == null ? catalogue.icon(reward) : encode.apply(item);
    }
}
