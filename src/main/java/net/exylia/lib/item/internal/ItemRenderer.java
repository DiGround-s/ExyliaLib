package net.exylia.lib.item.internal;

import net.exylia.lib.item.Appearance;
import net.exylia.lib.item.Item;
import net.exylia.lib.item.Source;
import net.exylia.lib.placeholder.Placeholders;
import net.exylia.lib.skull.SkullSource;
import net.exylia.lib.skull.Skulls;
import net.exylia.lib.text.Lines;
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
import java.util.LinkedHashMap;
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

    /**
     * What splits a row value into several lore lines.
     *
     * <p>The same spelling {@link ItemReader} splits templates on, so a value a
     * plugin passes in and a line written in a file mean the same thing by it.
     */
    private static final String NEWLINE = Lines.NEWLINE;

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
        return render(definition, viewer, owner, values, formatted, false, problems);
    }

    /**
     * Builds an item that is only ever looked at.
     *
     * <p>The same thing, with one liberty a menu slot can take and an item a
     * player keeps cannot: two item types describe themselves in a tooltip
     * nothing can hide, and drawing them as their model rather than as
     * themselves is the only way to a clean icon. See {@link #undescribed}.
     *
     * @param definition what to build
     * @param viewer     who it is for, or {@code null} for nobody in particular
     * @param owner      whose namespace stored values go under, or {@code null}
     * @param values     placeholder names to what they resolve to, without percent signs
     * @param formatted  which of those values are parsed rather than inserted literally
     * @param problems   where to report parts that could not be applied
     * @return the item
     * @since 1.67.0
     */
    public static ItemStack renderIcon(Item definition, Player viewer, Plugin owner,
                                       Map<String, String> values, Set<String> formatted,
                                       TraitApplier.Reporter problems) {
        return render(definition, viewer, owner, values, formatted, true, problems);
    }

    private static ItemStack render(Item definition, Player viewer, Plugin owner,
                                    Map<String, String> values, Set<String> formatted,
                                    boolean icon, TraitApplier.Reporter problems) {
        // Row values make the result specific to that row, so an item that
        // would otherwise be shared is not. Nor is an icon that comes out as a
        // different item than the definition names: the cache is keyed by the
        // definition alone, and a disguise stored under it would be handed to
        // whoever renders the same definition to give it away.
        boolean cacheable = values.isEmpty() && ItemCache.isCacheable(definition)
                && !(icon && disguises(definition));
        if (cacheable) {
            ItemStack held = ItemCache.get(definition);
            if (held != null) {
                return held;
            }
        }
        ItemStack item = build(definition, viewer, owner, values, formatted, icon, problems);
        if (cacheable) {
            ItemCache.put(definition, item);
        }
        return item;
    }

    private static ItemStack build(Item definition, Player viewer, Plugin owner,
                                   Map<String, String> values, Set<String> formatted,
                                   boolean icon, TraitApplier.Reporter problems) {
        UnaryOperator<String> resolve = resolver(viewer, values);
        ItemStack item = base(definition.source(), resolve, problems);
        // Before anything is written, so the writing lands on the item that
        // ends up on the screen rather than on the one it stood in for.
        if (icon) {
            item = undescribed(item, definition.appearance());
        }
        write(item, definition, viewer, values, formatted, resolve, problems);
        TraitApplier.apply(item, definition.traits(), owner, resolve, problems);
        // Last of all, and that is the whole point: every setItemMeta replaces
        // the item's component map, and TraitApplier calls it six times. Writing
        // this inside write() set the component and then had it wiped a moment
        // later — no warning, because it was written; no effect, because it was
        // gone by the time the item was handed over.
        hideAdditionalTooltip(item, definition.appearance(), problems);
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
        return value -> value(value, viewer, values);
    }

    /**
     * Resolves a placeholder that names something rather than saying it.
     *
     * <p>A material, a head's owner, a trim pattern: the answer is looked up in
     * a registry or sent to Mojang, so it has to come back with the letters it
     * was written with. Nothing here is drawn on the screen, which is why the
     * text module's presentation — small capitals above all — must not touch
     * it.
     *
     * <p>The bug this exists for is not subtle once seen: with small text on,
     * {@code material: "%effect_material%"} resolved to {@code ғɪʀᴇ_ᴄʜᴀʀɢᴇ},
     * no registry has ever heard of that, and every icon in the menu came out
     * as stone with one console line each.
     */
    static String value(String written, Player viewer, Map<String, String> values) {
        if (written.indexOf('%') < 0) {
            return written;
        }
        String filled = fill(written, values);
        // Through the placeholder module, never through Text: the value is
        // wanted, its presentation is not. Text would resolve the same names
        // and then draw the answer — small capitals, palette tokens, centring —
        // and a drawn registry key is not a registry key any more.
        return viewer == null ? filled : Placeholders.apply(filled, viewer);
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

    /**
     * Builds the object an icon string names, with nothing written on it.
     *
     * <p>The same grammar {@link #base} reads, asked without a definition, a
     * viewer or anywhere to report to — which is the question a stored icon is:
     * a column holds {@code DIAMOND_SWORD} or {@code bytes:...}, and something
     * has to draw it.
     *
     * <p>Falls back to paper rather than to stone, and swallows what
     * {@link #base} would have reported. A row nobody can see is a row nobody
     * can delete, and paper reads as "this could not be read" where a stone
     * block reads as somebody's configuration.
     */
    public static ItemStack icon(String source) {
        try {
            return switch (Source.of(source)) {
                case Source.OfSnapshot snapshot -> ItemStack.deserializeBytes(
                        Base64.getDecoder().decode(snapshot.base64()));
                case Source.OfMaterial material -> {
                    Material type = Registries.material(material.raw());
                    yield new ItemStack(type == null ? Material.PAPER : type);
                }
                case Source.OfHead head -> Skulls.of(head.head()).item();
                // No viewer, so there is nobody to resolve the owner against.
                // It draws as the plain head it will become.
                case Source.OfHeadTemplate ignored -> new ItemStack(Material.PLAYER_HEAD);
            };
        } catch (RuntimeException | LinkageError unreadable) {
            return new ItemStack(Material.PAPER);
        }
    }

    /** Builds the object the item starts as. */
    private static ItemStack base(Source declared, UnaryOperator<String> resolve,
                                  TraitApplier.Reporter problems) {
        return switch (effective(declared, resolve)) {
            // Already resolved by effective(), so the text is final here.
            case Source.OfMaterial material -> material(material.raw(), problems);
            // Whatever Skulls has now. A head it has not fetched comes back
            // plain rather than blocking; the caller that wants the finished
            // one asks for a handle instead.
            case Source.OfHead head -> Skulls.of(head.head()).item();
            case Source.OfHeadTemplate template -> head(template, resolve, problems);
            case Source.OfSnapshot snapshot -> snapshot(snapshot, problems);
        };
    }

    /**
     * What a source turns out to be once its placeholders are filled.
     *
     * <p>{@link Source} classifies a value when the file is read, and at that
     * point {@code material: "%arena_icon%"} is a material: no prefix, nothing
     * to read. What the row hands back very often is not one — an icon a server
     * owner picked is a head far more often than it is a block, and every icon
     * picker in the ecosystem stores {@code headbase-eyJ0…}, {@code playerhead-}
     * or a {@code bytes:} stack under exactly that key.
     *
     * <p>So a material that was written as a placeholder is read once more,
     * through the same grammar, rather than handed straight to the registry —
     * which has never heard of {@code headbase-eyJ0…} and answers with a stone
     * block and a console line per row. That is the whole bug this exists for.
     *
     * <p>Only a value that carried a placeholder comes through here: a literal
     * material was decided when the file was read and is not read twice. Nor is
     * the answer: whatever this returns is what gets built, so a placeholder
     * that resolved to another placeholder is reported by whoever it lands on
     * rather than chased around in a loop.
     *
     * @param declared what the file said
     * @param resolve  how a value carrying placeholders is filled in
     * @return what to build
     */
    static Source effective(Source declared, UnaryOperator<String> resolve) {
        if (declared instanceof Source.OfMaterial material && material.isDynamic()) {
            return Source.of(resolve.apply(material.raw()));
        }
        return declared;
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
        int limit = stackLimit(definition.appearance(), item.getAmount(),
                item.getType().getMaxStackSize());
        if (limit > 0) {
            meta.setMaxStackSize(limit);
        }
        item.setItemMeta(meta);
    }

    /**
     * The stack limit an item has to carry for its own count to be visible.
     *
     * <p>Since 1.20.5 the count is validated against the {@code max_stack_size}
     * component, so a menu icon written as {@code amount: 40} on a sword shows
     * as one sword: the material stacks to one and the number is quietly
     * dropped. The number is nearly always the whole point of the icon &mdash;
     * kills, tokens, how many of a thing a player owns &mdash; and every menu
     * that wanted one had to know to also write {@code max_stack_size}.
     *
     * <p>So an amount past the material's own limit raises the limit to match,
     * which is what ExyliaCommons did for every item it ever built. Two
     * differences, both deliberate: an explicit {@code max_stack_size} still
     * wins, because a file that names one means it; and an amount the material
     * already allows leaves the component off entirely, rather than stamping a
     * redundant one onto every icon in every menu.
     *
     * <p>No reflection. {@code ItemMeta.setMaxStackSize} is API as of 1.20.5 and
     * this library targets above it, so the method commons had to look up by
     * name is simply called.
     *
     * @param appearance   what the file asked for
     * @param amount       the count the item is being drawn with, already clamped
     * @param vanillaLimit what the material stacks to by itself
     * @return the limit to write, or {@code -1} to leave the material's own
     */
    static int stackLimit(Appearance appearance, int amount, int vanillaLimit) {
        if (appearance.maxStackSize() > 0) {
            return appearance.maxStackSize();
        }
        return amount > vanillaLimit ? amount : -1;
    }

    /**
     * Disables the tooltip block an item type writes for itself, when asked.
     *
     * <p>Called once the item is otherwise finished, never in the middle:
     * {@code setItemMeta} replaces the item's whole component map, so anything
     * written before the last of those calls is thrown away. That is not a
     * theoretical ordering note — it is why this had no effect at all until
     * 1.47.1 while reporting no problem, because the write did happen.
     *
     * <p>Package-private so the decision can be exercised without a server,
     * which is the part that was wrong: the flag was set all along.
     */
    static void hideAdditionalTooltip(ItemStack item, Appearance appearance,
                                      TraitApplier.Reporter problems) {
        if (appearance.hideAttributes()) {
            components.hideAdditionalTooltip(item, problems);
        }
    }

    /**
     * The two item types whose tooltip block nothing can hide.
     *
     * <p>{@code hide_additional_tooltip} was checked before an item was asked
     * to describe itself; {@code tooltip_display}, which replaced it in 1.21.5,
     * hides <em>components</em>, and the text a smithing template and a disc
     * fragment write comes from neither a component nor anything a server can
     * reach. Paper closed it as a vanilla limitation: hiding it is not possible
     * without hiding the whole tooltip, name included.
     *
     * <p>So the icon is drawn as a sheet of paper wearing the template's model.
     * The picture is the same one, the block is gone, and the model is only
     * filled in where the file did not name its own.
     *
     * <p>Icons only, and never an item a player keeps: a kit handing out a real
     * smithing template must hand out a smithing template. That is the whole
     * reason {@link #renderIcon} exists as a separate door.
     */
    private static ItemStack undescribed(ItemStack item, Appearance appearance) {
        if (!appearance.hideAttributes() || !describesItself(item.getType())
                || components.hidesWhatATypeWrites()) {
            return item;
        }
        ItemStack drawn = new ItemStack(Material.PAPER, item.getAmount());
        ItemMeta meta = drawn.getItemMeta();
        if (meta == null) {
            return item;
        }
        NamespacedKey written = appearance.model() == null
                ? null
                : NamespacedKey.fromString(appearance.model());
        // The type's own model where the file named none, and where it named
        // one nobody can read — appearance() reports that, and an icon drawn as
        // a blank sheet of paper is worse than the block it was hiding.
        meta.setItemModel(written == null ? item.getType().getKey() : written);
        drawn.setItemMeta(meta);
        return drawn;
    }

    /** Whether this type writes a tooltip block of its own that nothing hides. */
    static boolean describesItself(Material type) {
        return type.name().endsWith("SMITHING_TEMPLATE") || type == Material.DISC_FRAGMENT_5;
    }

    /**
     * Whether a definition would be drawn as something else.
     *
     * <p>Asked of the definition rather than of the finished item, because the
     * question is whether the answer may be cached, and that is decided before
     * anything is built. Only a literal material is ever cacheable — a source
     * written as a placeholder is dynamic — so reading the source is enough.
     */
    private static boolean disguises(Item definition) {
        if (!definition.appearance().hideAttributes()
                || !(definition.source() instanceof Source.OfMaterial written)) {
            return false;
        }
        Material type = Registries.material(written.raw());
        return type != null && describesItself(type) && !components.hidesWhatATypeWrites();
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

    /**
     * Renders the lore, expanding any row value that spans several lines.
     *
     * <p>{@code <nl>} in the template is split by {@link ItemReader} when the
     * file is read, which cannot reach a value that only exists at render time:
     * a description a plugin passes in as one row value. Splitting it here is
     * what lets {@code %effect_description%} hold more than one line.
     *
     * <p>Every expanded line is built from the <em>same</em> written string, so
     * they all hit the same parse-cache entry and each keeps whatever the
     * template puts around the placeholder. The cost of a multi-line value is
     * one substitution per line, never one parse per line.
     */
    // Package-private for the same reason as text(): expanding a row value into
    // lines is a decision worth testing, and it needs no live server.
    static List<Component> lore(List<String> written, Player viewer,
                                Map<String, String> values, Set<String> formatted) {
        List<Component> lines = new ArrayList<>(written.size());
        Map<String, String> normalized = normalized(values);
        for (String line : written) {
            int spans = spans(line, normalized);
            if (spans == 1) {
                lines.add(text(line, viewer, normalized, formatted));
                continue;
            }
            for (int index = 0; index < spans; index++) {
                lines.add(text(line, viewer, segment(normalized, index), formatted));
            }
        }
        return lines;
    }

    /**
     * Row values with every line-break spelling folded to the canonical marker.
     *
     * <p>A value read from a config arrives with a literal {@code \n}, the way
     * commons wrote it, and {@code spans} and {@code segment} only recognise
     * {@code <nl>}. Folding here keeps the writer's spellings honest in one
     * place instead of asking every plugin to normalise its own descriptions.
     */
    private static Map<String, String> normalized(Map<String, String> values) {
        boolean folded = false;
        Map<String, String> out = values;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String foldedValue = Lines.join(Lines.split(entry.getValue()));
            if (!foldedValue.equals(entry.getValue())) {
                if (!folded) {
                    out = new LinkedHashMap<>(values);
                    folded = true;
                }
                out.put(entry.getKey(), foldedValue);
            }
        }
        return out;
    }

    /**
     * How many lines this template line turns into.
     *
     * <p>Only values the line actually mentions count: a multi-line value that
     * belongs to some other line must not stretch this one.
     */
    private static int spans(String line, Map<String, String> values) {
        int spans = 1;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!entry.getValue().contains(NEWLINE)) {
                continue;
            }
            if (line.contains('%' + entry.getKey() + '%')) {
                spans = Math.max(spans, entry.getValue().split(NEWLINE, -1).length);
            }
        }
        return spans;
    }

    /**
     * The values as they stand on one expanded line.
     *
     * <p>A single-line value repeats, so text written beside a multi-line one
     * does not vanish after the first line. A multi-line value that runs out
     * contributes nothing further rather than repeating its last line.
     */
    private static Map<String, String> segment(Map<String, String> values, int index) {
        Map<String, String> segmented = new LinkedHashMap<>(values.size());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            if (!value.contains(NEWLINE)) {
                segmented.put(entry.getKey(), value);
                continue;
            }
            String[] parts = value.split(NEWLINE, -1);
            segmented.put(entry.getKey(), index < parts.length ? parts[index] : "");
        }
        return segmented;
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

    /**
     * What writes the data components an appearance needs.
     *
     * <p>A seam rather than a direct call, because {@code DataComponentTypes}
     * resolves every one of its constants against the server's registry in a
     * static initialiser: naming the class at all is enough to need a live
     * server, which is exactly what these tests do without.
     */
    interface Components {

        /**
         * Hides the tooltip block an item type writes for itself — the one an
         * {@code ItemFlag} does not cover on its own.
         *
         * <p>Reports rather than throws when the server does not know the
         * component: the versions this library runs on do not agree on it, and
         * a tooltip is not worth a menu that fails to open.
         */
        void hideAdditionalTooltip(ItemStack item, TraitApplier.Reporter problems);

        /**
         * Whether hiding it reaches the block an item type writes for itself.
         *
         * <p>True on the versions with {@code hide_additional_tooltip}, which
         * was checked before the item was asked to describe itself. False from
         * 1.21.5 on, where {@code tooltip_display} hides components and two
         * item types write text no component owns.
         */
        boolean hidesWhatATypeWrites();
    }

    /** The real one, kept out of line so the class above stays loadable. */
    private static Components components = ItemComponents.INSTANCE;

    /** Swaps in a stand-in. Tests only; returns what was there before. */
    static Components components(Components replacement) {
        Components previous = components;
        components = replacement;
        return previous;
    }

    // Package-private rather than private: writing an appearance onto meta is
    // the whole of this decision, and an ItemMeta can be stood in for, while an
    // ItemStack cannot be built at all without the server's registry.
    static void appearance(ItemMeta meta, Appearance appearance,
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
            // Everything vanilla writes on its own, not only the modifier
            // lines. A smithing template describes itself ("Applies to:
            // Armor"), a potion lists its effects and a firework its flight,
            // and a menu asking for a clean tooltip means all of it.
            //
            // ExyliaCommons applied ItemFlag.values() here, which also swept
            // up HIDE_ENCHANTS: an item asking to hide its attributes lost the
            // enchantment lines it meant to show. Named rather than "all", so
            // hiding an enchantment stays something a file asks for.
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES,
                    ItemFlag.HIDE_ADDITIONAL_TOOLTIP,
                    ItemFlag.HIDE_ARMOR_TRIM,
                    ItemFlag.HIDE_DYE);
            // The flag alone was never enough, and the API says why: a flag set
            // "without also setting the data they are hiding may not result in
            // the item flag being persisted". A smithing template holds no such
            // data — its block comes from the item type — so the flag was
            // dropped and the item kept describing itself. write() finishes the
            // job with the data component, which is defined against the type.
        }
        if (appearance.unbreakable()) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        if (appearance.modelData() >= 0) {
            meta.setCustomModelData(appearance.modelData());
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
