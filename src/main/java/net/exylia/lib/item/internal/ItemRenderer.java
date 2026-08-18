package net.exylia.lib.item.internal;

import net.exylia.lib.item.Appearance;
import net.exylia.lib.item.Item;
import net.exylia.lib.item.Source;
import net.exylia.lib.skull.SkullSource;
import net.exylia.lib.skull.Skulls;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Turns a definition into an item, for one player.
 *
 * <p>Nothing here parses configuration and nothing here goes to the network. A
 * head is asked of {@link Skulls}, which answers with what it has and fills in
 * the rest later; text goes through {@link Text}, which caches what it parses.
 * The rendering itself is metadata writes and nothing else.
 */
public final class ItemRenderer {

    private ItemRenderer() {
    }

    /**
     * Builds an item.
     *
     * <p>An item that cannot look different for different players is built once
     * and copied thereafter, which is what makes a menu full of decorations
     * cost nothing to redraw.
     *
     * @param definition what to build
     * @param viewer     who it is for, or {@code null} for nobody in particular
     * @param owner      whose namespace stored values go under, or {@code null}
     * @param problems   where to report parts that could not be applied
     * @return the item
     */
    public static ItemStack render(Item definition, Player viewer, Plugin owner,
                                   TraitApplier.Reporter problems) {
        return render(definition, viewer, owner, Map.of(), problems);
    }

    /**
     * Builds an item with extra values for its placeholders.
     *
     * <p>For a row of a list, whose values belong to the row rather than to the
     * viewer: the same template drawn twenty times with a different
     * {@code %kit_name%} each time.
     *
     * @param definition what to build
     * @param viewer     who it is for, or {@code null} for nobody in particular
     * @param owner      whose namespace stored values go under, or {@code null}
     * @param values     placeholder names to what they resolve to, without percent signs
     * @param problems   where to report parts that could not be applied
     * @return the item
     */
    public static ItemStack render(Item definition, Player viewer, Plugin owner,
                                   Map<String, String> values,
                                   TraitApplier.Reporter problems) {
        return render(definition, viewer, owner, values, Set.of(), problems);
    }

    /**
     * Builds an item where some values carry their own formatting.
     *
     * <p>A value named in {@code formatted} is parsed rather than inserted as
     * text, so a rank written {@code {highlight}&lMVP} in configuration arrives
     * as colour. Only for values the server owner wrote: anything a player
     * typed stays literal, or naming yourself {@code {error}} would recolour
     * whatever line you appear on.
     *
     * @param definition what to build
     * @param viewer     who it is for, or {@code null} for nobody in particular
     * @param owner      whose namespace stored values go under, or {@code null}
     * @param values     placeholder names to what they resolve to, without percent signs
     * @param formatted  which of those values are parsed rather than inserted literally
     * @param problems   where to report parts that could not be applied
     * @return the item
     */
    public static ItemStack render(Item definition, Player viewer, Plugin owner,
                                   Map<String, String> values, Set<String> formatted,
                                   TraitApplier.Reporter problems) {
        // Row values make the result specific to that row, so an item that
        // would otherwise be shared is not.
        boolean cacheable = values.isEmpty() && ItemCache.isCacheable(definition);
        if (cacheable) {
            ItemStack held = ItemCache.get(definition);
            if (held != null) {
                return held;
            }
        }
        ItemStack item = build(definition, viewer, owner, values, formatted, problems);
        if (cacheable) {
            ItemCache.put(definition, item);
        }
        return item;
    }

    private static ItemStack build(Item definition, Player viewer, Plugin owner,
                                   Map<String, String> values, Set<String> formatted,
                                   TraitApplier.Reporter problems) {
        UnaryOperator<String> resolve = resolver(viewer, values);
        ItemStack item = base(definition.source(), resolve, problems);
        write(item, definition, viewer, values, formatted, resolve, problems);
        TraitApplier.apply(item, definition.traits(), owner, resolve, problems);
        return item;
    }

    /**
     * Resolves placeholders to plain text.
     *
     * <p>Row values first and registered placeholders after, so a row can shadow
     * a global name — a list of players showing each row's {@code %player_name%}
     * rather than the viewer's, which is what a leaderboard means.
     *
     * <p>With nobody looking and no values, text is left as written: a
     * definition rendered for the console keeps its placeholders visible rather
     * than losing them.
     */
    private static UnaryOperator<String> resolver(Player viewer, Map<String, String> values) {
        if (viewer == null && values.isEmpty()) {
            return value -> value;
        }
        return value -> {
            if (value.indexOf('%') < 0) {
                return value;
            }
            String filled = fill(value, values);
            return viewer == null ? filled : Text.of(filled).forPlayer(viewer).plain();
        };
    }

    /** Substitutes row values into a string, before anything else looks at it. */
    private static String fill(String text, Map<String, String> values) {
        if (values.isEmpty()) {
            return text;
        }
        String filled = text;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            filled = filled.replace('%' + entry.getKey() + '%', entry.getValue());
        }
        return filled;
    }

    /** Builds the object the item starts as. */
    private static ItemStack base(Source source, UnaryOperator<String> resolve,
                                  TraitApplier.Reporter problems) {
        return switch (source) {
            case Source.OfMaterial material -> material(resolve.apply(material.raw()), problems);
            // Whatever Skulls has now. A head it has not fetched comes back
            // plain rather than blocking; the caller that wants the finished
            // one asks for a handle instead.
            case Source.OfHead head -> Skulls.of(head.head()).item();
            case Source.OfHeadTemplate template -> head(template, resolve, problems);
            case Source.OfSnapshot snapshot -> snapshot(snapshot, problems);
        };
    }

    private static ItemStack material(String name, TraitApplier.Reporter problems) {
        Material material = Registries.material(name);
        if (material == null) {
            problems.found("material", "there is no material called \"" + name + "\"");
            return new ItemStack(Material.STONE);
        }
        return new ItemStack(material);
    }

    /** Resolves a head whose owner was written as a placeholder. */
    private static ItemStack head(Source.OfHeadTemplate template, UnaryOperator<String> resolve,
                                  TraitApplier.Reporter problems) {
        String raw = template.raw();
        int separator = Math.max(raw.indexOf('-'), 0);
        int colon = raw.indexOf(':');
        if (colon >= 0 && (separator == 0 || colon < separator)) {
            separator = colon;
        }
        String payload = resolve.apply(raw.substring(separator + 1));
        if (payload.isBlank() || payload.indexOf('%') >= 0) {
            // The row had nobody in it, or the placeholder had no value. A
            // plain head is the honest answer; a lookup for "%player_name%"
            // would be a request to Mojang for a player who does not exist.
            problems.found("head", "\"" + raw + "\" did not resolve to anybody");
            return new ItemStack(Material.PLAYER_HEAD);
        }
        SkullSource source = template.kind().sourceOf(payload);
        return Skulls.of(source).item();
    }

    private static ItemStack snapshot(Source.OfSnapshot snapshot, TraitApplier.Reporter problems) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(snapshot.base64()));
        } catch (RuntimeException malformed) {
            problems.found("material", "the serialised item could not be read");
            return new ItemStack(Material.PAPER);
        }
    }

    /** Writes everything that is not the object itself. */
    private static void write(ItemStack item, Item definition, Player viewer,
                              Map<String, String> values, Set<String> formatted,
                              UnaryOperator<String> resolve,
                              TraitApplier.Reporter problems) {
        amount(item, definition.amount(), resolve);

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            // Air, and a few oddities. Nothing to write on.
            return;
        }
        if (definition.name() != null) {
            meta.displayName(text(definition.name(), viewer, values, formatted));
        }
        if (!definition.lore().isEmpty()) {
            meta.lore(lore(definition.lore(), viewer, values, formatted));
        }
        enchantments(meta, definition.enchantments(), resolve, problems);
        appearance(meta, definition.appearance(), problems);
        item.setItemMeta(meta);
    }

    /**
     * Sets the stack size.
     *
     * <p>Clamped rather than rejected: an amount driven by a placeholder can
     * legitimately arrive as zero — a player who owns none of something — and
     * an empty slot is not what the menu meant to show.
     */
    private static void amount(ItemStack item, String written, UnaryOperator<String> resolve) {
        String value = resolve.apply(written);
        try {
            item.setAmount(Math.clamp(Integer.parseInt(value.trim()), 1, 99));
        } catch (NumberFormatException notANumber) {
            item.setAmount(1);
        }
    }

    /**
     * Renders one line of item text.
     *
     * <p>Italics off unless asked for: vanilla italicises item names and lore,
     * and every plugin that forgets it ends up with a slanted menu.
     */
    // Package-private rather than private: this is the whole of what a row's
    // values do to a line, and it is the only part that can be exercised
    // without a live server, since an ItemStack needs the registry.
    static Component text(String written, Player viewer, Map<String, String> values,
                          Set<String> formatted) {
        Text built = Text.of(written);
        // Row values are substituted on the component tree rather than into the
        // string, so the template itself is parsed once and shared by every row
        // drawn from it. Literal unless the caller said otherwise: what a
        // player typed is data, and a colour written in a config is not.
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String placeholder = '%' + entry.getKey() + '%';
            built = formatted.contains(entry.getKey())
                    ? built.withFormatted(placeholder, entry.getValue())
                    : built.with(placeholder, entry.getValue());
        }
        if (viewer != null) {
            built = built.forPlayer(viewer);
        }
        return built.build()
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static List<Component> lore(List<String> written, Player viewer,
                                        Map<String, String> values, Set<String> formatted) {
        List<Component> lines = new ArrayList<>(written.size());
        for (String line : written) {
            lines.add(text(line, viewer, values, formatted));
        }
        return lines;
    }

    private static void enchantments(ItemMeta meta, Map<String, Integer> enchantments,
                                     UnaryOperator<String> resolve,
                                     TraitApplier.Reporter problems) {
        for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
            String name = resolve.apply(entry.getKey());
            Enchantment enchantment = Registries.enchantment(name);
            if (enchantment == null) {
                problems.found("enchantment", "there is no enchantment called \"" + name + "\"");
                continue;
            }
            meta.addEnchant(enchantment, entry.getValue(), true);
        }
    }

    private static void appearance(ItemMeta meta, Appearance appearance,
                                   TraitApplier.Reporter problems) {
        if (appearance.isPlain()) {
            return;
        }
        if (appearance.glow()) {
            // The override rather than a fake enchantment: it glows without
            // putting a line in the tooltip, which is what the flag meant all
            // along and what commons faked with Unbreaking plus HIDE_ENCHANTS.
            meta.setEnchantmentGlintOverride(true);
        }
        if (appearance.hideTooltip()) {
            meta.setHideTooltip(true);
        }
        if (appearance.hideAttributes()) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        }
        if (appearance.unbreakable()) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        if (appearance.modelData() >= 0) {
            meta.setCustomModelData(appearance.modelData());
        }
        if (appearance.maxStackSize() > 0) {
            meta.setMaxStackSize(appearance.maxStackSize());
        }
        // Parsed by nobody in commons, so files asking to hide enchantment
        // lines have been showing them for years.
        for (String written : appearance.flags()) {
            ItemFlag flag = Registries.flag(written);
            if (flag == null) {
                problems.found("flag", "there is no item flag called \"" + written + "\"");
                continue;
            }
            meta.addItemFlags(flag);
        }
        if (appearance.model() != null) {
            NamespacedKey key = NamespacedKey.fromString(appearance.model());
            if (key == null) {
                problems.found("item_model",
                        "\"" + appearance.model() + "\" is not a namespace:key");
            } else {
                meta.setItemModel(key);
            }
        }
        if (appearance.tooltipStyle() != null) {
            NamespacedKey key = NamespacedKey.fromString(appearance.tooltipStyle());
            if (key == null) {
                problems.found("tooltip_style",
                        "\"" + appearance.tooltipStyle() + "\" is not a namespace:key");
            } else {
                meta.setTooltipStyle(key);
            }
        }
    }
}
