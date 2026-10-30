package net.exylia.lib.spatial;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;
import net.exylia.lib.cosmetic.CosmeticPurchases;
import net.exylia.lib.cosmetic.Price;
import net.exylia.lib.spatial.internal.Catalogs;
import net.exylia.lib.stage.StageLook;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.crate.PluginCrates;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A cosmetic shop and wardrobe in one spatial menu: what a plugin sells, tried on as a draft, bought in
 * place, saved, taken off. The plugin declares what it sells and how it previews; the catalogue answers
 * the layout's {@code save}, {@code revert}, {@code unequip}, {@code favourite}, {@code filter} and
 * {@code crate} actions and fills the {@code %catalog_...%} words.
 *
 * <pre>{@code
 * SpatialMenus.of(plugin).registerCatalog("effects", Main.class, catalog -> catalog
 *         .offers("effects", ctx -> effects(ctx.viewer()))                   // List<Offer>, read on every redraw
 *         .equips(player -> worn(player), (player, id) -> wear(player, id))   // one worn at a time: SAVE wears it
 *         .unequip(player -> takeOff(player))
 *         .preview((ctx, id) -> SpatialPreview.sequence(sequenceOf(id), null))
 *         .categories(ctx -> categories(), true)
 *         .favourites(this::starred, this::star)
 *         .crate(() -> crates)
 *         .words(ctx -> lines.catalog())
 *         .menu(menu -> menu.action("replay", SpatialContext::replay)));
 * }</pre>
 *
 * <p>SAVE: a draft whose parts the player owns is saved at once; one with parts they do not own reads
 * {@code BUY & SAVE} with the total, a first click turns it into {@code CONFIRM} (in place, no
 * inventory over the world), a second click buys every part through {@link CosmeticPurchases} and then
 * saves. A part nobody sells blocks it and the status line names it.
 *
 * @since 1.416.0
 */
public final class SpatialCatalog {

    /** The filter that shows every offer. Not a valid category id, so it cannot clash with one. */
    public static final String ALL = "*";
    /** The filter that shows the player's favourites. */
    public static final String FAVOURITES = "favourites";

    /** The layout actions the catalogue answers, unless the plugin registers one of the same name. */
    public static final String SAVE = "save";
    public static final String REVERT = "revert";
    public static final String UNEQUIP = "unequip";
    public static final String FAVOURITE = "favourite";
    public static final String FILTER = "filter";
    public static final String CRATE = "crate";

    /** The menu's state key holding the open filter. */
    static final String FILTER_KEY = "catalog_filter";

    // ── What a plugin declares ────────────────────────────────────────────

    /**
     * How a part is bought: what it costs, how the player knows it, and the plugin's own ownership and
     * grant, as {@link CosmeticPurchases#purchase} takes them.
     *
     * @param id    the id the purchase is booked under ({@code <plugin>:buy:<id>}), and the basket's key
     * @param name  how the player knows it, with colours
     * @param owned whether they own it now, by every route the plugin knows
     * @param grant gives it to them; answers whether it did ({@code false} refunds)
     */
    public record Purchase(@NotNull String id, @NotNull String name, @NotNull Price price,
                           @NotNull BooleanSupplier owned, @NotNull Supplier<CompletableFuture<Boolean>> grant) {

        public Purchase {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(owned, "owned");
            Objects.requireNonNull(grant, "grant");
        }

        /** A crate reward bought with money: owned and granted through the plugin's crates. */
        public static @NotNull Purchase crate(@NotNull PluginCrates crates, @NotNull Player player, @NotNull String id,
                                              @NotNull String name, @NotNull Price price) {
            UUID uuid = player.getUniqueId();
            return new Purchase(id, name, price, () -> crates.owns(uuid, id), () -> crates.unlock(uuid, id));
        }
    }

    /**
     * One thing on sale. The catalogue draws it as its {@code entry} with the state that fits: worn,
     * owned, or locked with its price (the theme's locked line when nobody sells it).
     *
     * @param entry    how it is drawn: id, icon, name, subtitle
     * @param owned    whether the player has it
     * @param equipped whether they wear it now (only read when owned)
     * @param category the category a {@link #categories filter} shows it under; {@code null} for none
     * @param purchase how to buy it when not owned; {@code null} when it is not for sale
     */
    public record Offer(@NotNull SpatialEntry entry, boolean owned, boolean equipped, @Nullable String category,
                        @Nullable Purchase purchase) {

        public Offer {
            Objects.requireNonNull(entry, "entry");
        }

        /** Something the player has. */
        public static @NotNull Offer owned(@NotNull SpatialEntry entry) {
            return new Offer(entry, true, false, null, null);
        }

        /** Something they do not have: for sale through {@code purchase}, or not at all ({@code null}). */
        public static @NotNull Offer locked(@NotNull SpatialEntry entry, @Nullable Purchase purchase) {
            return new Offer(entry, false, false, null, purchase);
        }

        public @NotNull Offer withEquipped(boolean equipped) {
            return new Offer(entry, owned, equipped, category, purchase);
        }

        public @NotNull Offer withCategory(@Nullable String category) {
            return new Offer(entry, owned, equipped, category, purchase);
        }

        public @NotNull String id() {
            return entry.id();
        }

        /** How the player knows it: the purchase's name, else the entry's. */
        public @NotNull String name() {
            return purchase != null ? purchase.name() : entry.name();
        }

        /** Whether the player would have to buy it, and can. */
        public boolean buyable() {
            return !owned && purchase != null;
        }
    }

    /** A category the filter steps through. */
    public record Category(@NotNull String id, @NotNull String name) {
    }

    /**
     * Every word the catalogue draws. A record a plugin can nest in its lang file
     * ({@code @Key("catalog") SpatialCatalog.Words catalog}) to translate them; {@code new Words()} is
     * the English.
     */
    public record Words(
            @Comment("SAVE: nothing to save, a draft to save, one to buy first (%price% the total), and its second click.")
            @Key("save") String save,
            @Key("saved") String saved,
            @Key("buy") String buy,
            @Key("confirm") String confirm,
            @Comment("SAVE while a part of the draft is not for sale.")
            @Key("locked") String locked,
            @Comment("The line next to SAVE. %name% is what is tried, %price% the total, %count% the parts to buy,")
            @Comment("%part% the part nobody sells.")
            @Key("status-saved") String statusSaved,
            @Key("status-unsaved") String statusUnsaved,
            @Key("status-buy") String statusBuy,
            @Key("status-confirm") String statusConfirm,
            @Key("status-locked") String statusLocked,
            @Comment("REVERT and UNEQUIP read these while there is something to undo or take off, and nothing otherwise.")
            @Key("revert") String revert,
            @Key("unequip") String unequip,
            @Comment("The favourite button, for the entry tried or looked at.")
            @Key("favourite") String favourite,
            @Key("unfavourite") String unfavourite,
            @Comment("The filter button. %category% is the open filter's name.")
            @Key("filter") String filter,
            @Key("filter-all") String filterAll,
            @Key("filter-favourites") String filterFavourites,
            @Comment("The crate button. %keys% is how many keys the player has.")
            @Key("crate") String crate,
            @Comment("Under an entry for sale. %price% is what it costs.")
            @Key("price") String price) {

        public Words() {
            this("{success}&lSAVE", "{muted}Saved {success}✔", "{highlight}&lBUY & SAVE", "{warning}&lCONFIRM",
                    "{muted}&lLOCKED",
                    "{muted}Everything is saved", "{warning}● Unsaved changes",
                    "{muted}Buy {highlight}%count% {muted}» {warning}%price%",
                    "{muted}Click again to pay {warning}%price%",
                    "{error}✘ %part% {muted}is not for sale",
                    "{info}&l↺ REVERT", "{error}&l✘ UNEQUIP",
                    "{highlight}&l☆ FAVOURITE", "{highlight}&l★ FAVOURITE",
                    "{letters_black}« {info}%category% {letters_black}»", "All", "Favourites",
                    "{primary}&lCRATE {letters_black}» {highlight}%keys%",
                    "{warning}%price%");
        }
    }

    // ── State ─────────────────────────────────────────────────────────────

    private final SpatialMenus menus;
    private final SpatialMenu menu;
    private final Map<String, Function<SpatialContext, List<Offer>>> offers = new LinkedHashMap<>();
    private @Nullable String primary;
    private boolean ownedFirst;
    private @Nullable Function<SpatialContext, List<Category>> categories;
    private boolean allFilter = true;
    private @Nullable BiPredicate<Player, String> starred;
    private @Nullable BiConsumer<Player, String> star;
    private @Nullable Predicate<SpatialContext> wearing;
    private @Nullable Function<SpatialContext, String> unequip;
    private @Nullable Function<Player, String> worn;
    private @Nullable BiFunction<Player, String, String> equip;
    private @Nullable BiFunction<SpatialContext, String, SpatialPreview> preview;
    private @Nullable Function<SpatialContext, List<Offer>> basket;
    private @Nullable Supplier<PluginCrates> crates;
    private Function<SpatialContext, Words> words = ctx -> DEFAULT_WORDS;
    private @Nullable Function<SpatialContext, Values> values;

    private final Map<String, String> aliases = new LinkedHashMap<>();

    private static final Words DEFAULT_WORDS = new Words();

    SpatialCatalog(SpatialMenus menus, SpatialMenu menu) {
        this.menus = menus;
        this.menu = menu;
    }

    /** The menu underneath, for anything a catalogue does not declare: extra sources, actions, guards. */
    public @NotNull SpatialMenu menu() {
        return menu;
    }

    /** {@link #menu()} handed to {@code more}, so a declaration reads as one chain. */
    public @NotNull SpatialCatalog menu(@NotNull Consumer<SpatialMenu> more) {
        more.accept(menu);
        return this;
    }

    /**
     * A list of offers, drawn by the layout panel or carousel whose {@code source} is {@code source}. The
     * first one declared is the catalogue's main list: the one filtered, favoured, equipped and previewed.
     * Read on every redraw, on the viewer's thread: memory only.
     */
    public @NotNull SpatialCatalog offers(@NotNull String source, @NotNull Function<SpatialContext, List<Offer>> offers) {
        this.offers.put(Objects.requireNonNull(source, "source"), Objects.requireNonNull(offers, "offers"));
        if (primary == null) primary = source;
        return this;
    }

    /** Shows the main list's owned offers first (not on the favourites filter, which keeps the player's order). */
    public @NotNull SpatialCatalog ownedFirst() {
        this.ownedFirst = true;
        return this;
    }

    /**
     * Categories for the {@code filter} action to step through, each offer shown under its
     * {@link Offer#category}. {@code all} puts "every offer" first; without it the first category opens.
     */
    public @NotNull SpatialCatalog categories(@NotNull Function<SpatialContext, List<Category>> categories, boolean all) {
        this.categories = Objects.requireNonNull(categories, "categories");
        this.allFilter = all;
        return this;
    }

    /** Favourites: the {@code favourite} action stars what is tried, and the filter gains a favourites step. */
    public @NotNull SpatialCatalog favourites(@NotNull BiPredicate<Player, String> starred,
                                              @NotNull BiConsumer<Player, String> toggle) {
        this.starred = Objects.requireNonNull(starred, "starred");
        this.star = Objects.requireNonNull(toggle, "toggle");
        return this;
    }

    /**
     * One worn at a time, from the main list: what is worn is the saved state, the menu opens on it, and
     * SAVE wears the offer tried ({@code equip} answers a refusal line, {@code ""} to refuse silently, or
     * {@code null}). Without this, declare {@link SpatialMenu#saved} and {@link SpatialMenu#onSave} on
     * {@link #menu()} for a draft of several parts.
     */
    public @NotNull SpatialCatalog equips(@NotNull Function<Player, String> worn,
                                          @NotNull BiFunction<Player, String, String> equip) {
        this.worn = Objects.requireNonNull(worn, "worn");
        this.equip = Objects.requireNonNull(equip, "equip");
        return this;
    }

    /** UNEQUIP for an {@link #equips} catalogue: shown while something is worn, takes it off at once. */
    public @NotNull SpatialCatalog unequip(@NotNull Function<Player, String> takeOff) {
        Objects.requireNonNull(takeOff, "takeOff");
        return unequip(ctx -> worn != null && worn.apply(ctx.viewer()) != null, ctx -> takeOff.apply(ctx.viewer()));
    }

    /** UNEQUIP: shown while {@code wearing}, takes everything off at once (a refusal line, or {@code null}). */
    public @NotNull SpatialCatalog unequip(@NotNull Predicate<SpatialContext> wearing,
                                           @NotNull Function<SpatialContext, String> unequip) {
        this.wearing = Objects.requireNonNull(wearing, "wearing");
        this.unequip = Objects.requireNonNull(unequip, "unequip");
        return this;
    }

    /**
     * The try-on: what stands on the pedestal for the main list's chosen offer ({@code null} id: none is
     * chosen). {@link SpatialPreview#npc(Player, Map)} for armour and skins, {@link SpatialPreview#sequence}
     * for an effect, an emote ({@code sequence(emote, viewer)}, the viewer's own skin performing) or a
     * trail, {@link SpatialPreview#item} for a shield or a banner.
     */
    public @NotNull SpatialCatalog preview(@NotNull BiFunction<SpatialContext, String, SpatialPreview> preview) {
        this.preview = Objects.requireNonNull(preview, "preview");
        return this;
    }

    /**
     * The parts the draft needs that the player does not own, for SAVE to buy first. By default, for each
     * offers list whose selection is drafted, the chosen offer when it is not owned; a draft of several
     * parts (a look made of a pattern and a material) gives its own.
     */
    public @NotNull SpatialCatalog basket(@NotNull Function<SpatialContext, List<Offer>> basket) {
        this.basket = Objects.requireNonNull(basket, "basket");
        return this;
    }

    /** The crate button: closes the menu and opens the plugin's crate. */
    public @NotNull SpatialCatalog crate(@NotNull Supplier<PluginCrates> crates) {
        this.crates = Objects.requireNonNull(crates, "crates");
        return this;
    }

    /** The catalogue's words, per viewer, usually from the plugin's lang files. */
    public @NotNull SpatialCatalog words(@NotNull Function<SpatialContext, Words> words) {
        this.words = Objects.requireNonNull(words, "words");
        return this;
    }

    /**
     * The plugin's own placeholders, beside the catalogue's (use this, not {@link SpatialMenu#values}). They
     * are filled first, so a value may itself read {@code %catalog_save%}.
     */
    public @NotNull SpatialCatalog values(@Nullable Function<SpatialContext, Values> values) {
        this.values = values;
        return this;
    }

    /** Answers {@code legacy}, an action name an older layout wrote, as {@code action}: {@code alias("primary", SAVE)}. */
    public @NotNull SpatialCatalog alias(@NotNull String legacy, @NotNull String action) {
        aliases.put(Objects.requireNonNull(legacy, "legacy"), Objects.requireNonNull(action, "action"));
        return this;
    }

    // ── What the plugin reads back ────────────────────────────────────────

    /** The open filter: {@link #ALL}, {@link #FAVOURITES} or a category id. */
    public static @NotNull String filter(@NotNull SpatialContext ctx) {
        return ctx.get(FILTER_KEY, ALL);
    }

    /** The entry under the crosshair in {@code source}, else the chosen one: what an information panel describes. */
    public static @Nullable String shown(@NotNull SpatialContext ctx, @NotNull String source) {
        String hovered = ctx.hovered(source);
        return hovered != null ? hovered : ctx.selected(source);
    }

    // ── Installing ────────────────────────────────────────────────────────

    void install() {
        if (primary == null) throw new IllegalStateException("A catalogue needs offers(source, ...)");
        for (String source : offers.keySet()) menu.source(source, ctx -> entries(ctx, source));
        menu.values(this::words);
        if (preview != null) menu.preview(ctx -> {
            String id = ctx.selected(primary);
            SpatialPreview shown = id == null ? null : preview.apply(ctx, id);
            return shown == null ? SpatialPreview.none() : shown;
        });
        if (worn != null) {
            if (menu.saved == null) menu.saved(ctx -> Collections.singletonMap(primary, worn.apply(ctx.viewer())));
            if (menu.save == null) menu.onSave(this::equipChosen);
            Consumer<SpatialContext> opened = menu.onOpen;
            menu.onOpen(ctx -> {
                startOnWorn(ctx);
                if (opened != null) opened.accept(ctx);
            });
        }
        action(SAVE, this::save);
        if (unequip != null) action(UNEQUIP, this::takeOff);
        if (starred != null) action(FAVOURITE, this::favourite);
        if (categories != null || starred != null) action(FILTER, this::nextFilter);
        if (crates != null) action(CRATE, this::openCrate);
        aliases.forEach((legacy, action) -> {
            Consumer<SpatialContext> run = menu.actions.get(action);
            if (run != null) action(legacy, run);
        });
    }

    /**
     * The open filter, set on first reading: every offer, or the first category when the catalogue has
     * no "every offer" step. A category that is gone (a reload) falls back the same way.
     */
    private String current(SpatialContext ctx) {
        String filter = ctx.get(FILTER_KEY, "");
        if (FAVOURITES.equals(filter) && starred != null) return filter;
        List<Category> all = categories == null ? List.of() : categories.apply(ctx);
        if (ALL.equals(filter) && (allFilter || all.isEmpty())) return filter;
        for (Category category : all) if (category.id().equals(filter)) return filter;
        String start = allFilter || all.isEmpty() ? ALL : all.get(0).id();
        ctx.set(FILTER_KEY, start);
        return start;
    }

    private void action(String id, Consumer<SpatialContext> run) {
        if (!menu.actions.containsKey(id)) menu.action(id, run);
    }

    // ── Lists ─────────────────────────────────────────────────────────────

    /** Every offer of a list, unfiltered. */
    private List<Offer> all(SpatialContext ctx, String source) {
        Function<SpatialContext, List<Offer>> read = offers.get(source);
        List<Offer> list = read == null ? null : read.apply(ctx);
        return list == null ? List.of() : list;
    }

    /** A list as it is shown: the main one filtered and ordered. */
    private List<Offer> shownOffers(SpatialContext ctx, String source) {
        List<Offer> list = all(ctx, source);
        if (!source.equals(primary)) return list;
        String filter = current(ctx);
        Player player = ctx.viewer();
        if (FAVOURITES.equals(filter) && starred != null) {
            List<Offer> mine = new ArrayList<>();
            for (Offer offer : list) if (starred.test(player, offer.id())) mine.add(offer);
            return mine;
        }
        if (!ALL.equals(filter)) {
            List<Offer> in = new ArrayList<>();
            for (Offer offer : list) if (filter.equals(offer.category())) in.add(offer);
            list = in;
        }
        return ownedFirst ? Catalogs.firstWhere(list, Offer::owned) : list;
    }

    private @Nullable Offer find(SpatialContext ctx, String source, @Nullable String id) {
        if (id == null) return null;
        for (Offer offer : all(ctx, source)) if (offer.id().equals(id)) return offer;
        return null;
    }

    private List<SpatialEntry> entries(SpatialContext ctx, String source) {
        Words text = words.apply(ctx);
        List<SpatialEntry> entries = new ArrayList<>();
        for (Offer offer : shownOffers(ctx, source)) entries.add(entry(offer, text));
        return entries;
    }

    private static SpatialEntry entry(Offer offer, Words text) {
        SpatialEntry entry = offer.entry();
        if (offer.owned()) {
            if (offer.equipped()) return entry.withState(SpatialEntry.State.EQUIPPED);
            return entry.locked() ? entry.withState(SpatialEntry.State.AVAILABLE) : entry;
        }
        entry = entry.withState(SpatialEntry.State.LOCKED);
        if (entry.price() == null && offer.purchase() != null) {
            entry = entry.withPrice(text.price().replace("%price%", offer.purchase().price().format()));
        }
        return entry;
    }

    // ── Words ─────────────────────────────────────────────────────────────

    /** What SAVE has to buy, and what it reads. */
    private record Basket(List<Offer> parts, @Nullable Offer blocked, String total, @Nullable Price sum) {
        boolean empty() {
            return parts.isEmpty();
        }

        String key() {
            StringBuilder key = new StringBuilder(SAVE).append(':');
            for (Offer part : parts) key.append(part.id()).append(',');
            return key.toString();
        }
    }

    private Basket basket(SpatialContext ctx) {
        List<Offer> parts = new ArrayList<>();
        if (basket != null) {
            List<Offer> given = basket.apply(ctx);
            if (given != null) for (Offer part : given) if (!part.owned()) parts.add(part);
        } else {
            var changed = ctx.changed();
            for (String source : offers.keySet()) {
                if (!changed.contains(source)) continue;
                Offer chosen = find(ctx, source, ctx.selected(source));
                if (chosen != null && !chosen.owned()) parts.add(chosen);
            }
        }
        // One part bought once, however many pieces of the draft use it.
        Map<String, Offer> unique = new LinkedHashMap<>();
        for (Offer part : parts) unique.putIfAbsent(part.purchase() != null ? part.purchase().id() : "#" + part.id(), part);
        parts = List.copyOf(unique.values());
        Offer blocked = null;
        List<Price> prices = new ArrayList<>();
        for (Offer part : parts) {
            if (part.purchase() == null) {
                if (blocked == null) blocked = part;
            } else {
                prices.add(part.purchase().price());
            }
        }
        Price sum = Catalogs.total(prices);
        String total = sum != null ? sum.format()
                : String.join(" + ", prices.stream().map(Price::format).toList());
        return new Basket(parts, blocked, total, sum);
    }

    private Values words(SpatialContext ctx) {
        Words text = words.apply(ctx);
        Player player = ctx.viewer();
        Basket basket = basket(ctx);
        boolean dirty = ctx.dirty();
        Offer chosen = find(ctx, primary, ctx.selected(primary));
        String name = chosen == null ? "" : chosen.name();
        boolean confirming = ctx.confirming(SAVE);

        String save;
        String status;
        if (!dirty) {
            save = text.saved();
            status = text.statusSaved();
        } else if (basket.empty()) {
            save = text.save();
            status = text.statusUnsaved();
        } else if (basket.blocked() != null) {
            save = text.locked();
            status = text.statusLocked();
        } else if (confirming) {
            save = text.confirm();
            status = text.statusConfirm();
        } else {
            save = text.buy();
            status = text.statusBuy();
        }
        String part = basket.blocked() == null ? "" : basket.blocked().name();
        // The plugin's first, so one of its words may hold a %catalog_...% (an old layout's name for SAVE).
        Values values = Values.of();
        if (this.values != null) {
            Values own = this.values.apply(ctx);
            if (own != null) own.map().forEach(values::put);
        }
        values.put("catalog_save", fillIn(save, name, basket, part))
                .put("catalog_status", fillIn(status, name, basket, part))
                .put("catalog_total", basket.total())
                .put("catalog_name", name)
                .put("catalog_revert", dirty ? text.revert() : "")
                .put("catalog_unequip", wearing != null && wearing.test(ctx) ? text.unequip() : "");
        if (starred != null) {
            String shown = shown(ctx, primary);
            values.put("catalog_favourite", shown == null ? ""
                    : starred.test(player, shown) ? text.unfavourite() : text.favourite());
        }
        if (categories != null || starred != null) {
            values.put("catalog_filter", text.filter().replace("%category%", filterName(ctx, text)));
        }
        PluginCrates crate = crates == null ? null : crates.get();
        values.put("catalog_crate", crate == null ? "" : text.crate().replace("%keys%", String.valueOf(crate.keys(player.getUniqueId()))));
        return values;
    }

    private static String fillIn(String line, String name, Basket basket, String part) {
        return line.replace("%name%", name).replace("%price%", basket.total())
                .replace("%count%", String.valueOf(basket.parts().size())).replace("%part%", part);
    }

    private String filterName(SpatialContext ctx, Words text) {
        String filter = current(ctx);
        if (ALL.equals(filter)) return text.filterAll();
        if (FAVOURITES.equals(filter)) return text.filterFavourites();
        if (categories != null) {
            for (Category category : categories.apply(ctx)) if (category.id().equals(filter)) return category.name();
        }
        return text.filterAll();
    }

    // ── Actions ───────────────────────────────────────────────────────────

    /**
     * SAVE: saved at once when the draft needs nothing bought; otherwise the first click asks (CONFIRM
     * with the total, after checking the player can pay it) and the second buys every part, then saves.
     */
    private void save(SpatialContext ctx) {
        Basket basket = basket(ctx);
        if (!ctx.dirty() || basket.empty() || basket.blocked() != null) {
            ctx.disarm();
            ctx.save();
            return;
        }
        Player player = ctx.viewer();
        String key = basket.key();
        if (!ctx.confirming(key)) {
            // Refused before CONFIRM is offered: nobody confirms what they cannot pay.
            if (basket.sum() != null && CosmeticPurchases.check(player, names(basket), basket.sum(), () -> false) != null) {
                refuse(player, null);
                ctx.disarm();
                ctx.refresh();
                return;
            }
            ctx.confirm(key);
            ctx.refresh();
            return;
        }
        ctx.confirm(key);
        buyAll(ctx, basket.parts());
    }

    private static String names(Basket basket) {
        List<String> names = new ArrayList<>();
        for (Offer part : basket.parts()) names.add(part.name());
        return String.join(" + ", names);
    }

    /** Buys the parts one after another, stopping at the first that fails (already told), then saves. */
    private void buyAll(SpatialContext ctx, List<Offer> parts) {
        Player player = ctx.viewer();
        var plugin = menus.plugin();
        // Each part on the player's next tick: a purchase lets go of the player's one-at-a-time lock only
        // after its answer is handed on, so the next one started straight from it would be refused as busy.
        java.util.concurrent.Executor onPlayer = run -> Tasks.of(plugin).runAtEntity(player, run);
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
        for (Offer part : parts) {
            Purchase purchase = part.purchase();
            chain = chain.thenComposeAsync(ok -> !ok ? CompletableFuture.completedFuture(false)
                    : CosmeticPurchases.purchase(plugin, player, purchase.id(), purchase.name(), purchase.price(),
                    purchase.owned(), purchase.grant()).thenApply(CosmeticPurchases.Result::bought), onPlayer);
        }
        chain.whenComplete((bought, failure) -> {
            if (failure != null) plugin.getLogger().warning("A catalogue purchase for " + player.getName() + " failed: " + failure);
            Tasks.of(plugin).runAtEntity(player, () -> {
                // Bought and saved; or, when a part failed, drawn again with what they own now.
                if (Boolean.TRUE.equals(bought) && failure == null) ctx.save();
                else ctx.refresh();
            });
        });
    }

    /** The {@link #equips} save: wears the chosen offer when it is theirs and not worn already. */
    private @Nullable String equipChosen(SpatialContext ctx) {
        String id = ctx.selected(primary);
        if (id == null || worn == null || equip == null) return "";
        Offer chosen = find(ctx, primary, id);
        if (chosen == null || !chosen.owned()) return "";
        if (id.equals(worn.apply(ctx.viewer()))) return "";
        return equip.apply(ctx.viewer(), id);
    }

    /** Opens on what is worn, on the page it is on; nothing chosen when nothing is worn. */
    private void startOnWorn(SpatialContext ctx) {
        String id = worn == null ? null : worn.apply(ctx.viewer());
        if (id == null) return;
        ctx.select(primary, id);
        pageTo(ctx, id);
    }

    private void pageTo(SpatialContext ctx, @Nullable String id) {
        List<Offer> shown = shownOffers(ctx, primary);
        int at = -1;
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).id().equals(id)) {
                at = i;
                break;
            }
        }
        ctx.page(primary, Catalogs.pageOf(at, perPage()));
    }

    /** How many entries of the main list a page holds, from the layout. */
    private int perPage() {
        var file = menus.layout(menu.id());
        if (file == null) return 1;
        SpatialLayout layout = file.get();
        if (primary.equals(layout.carousel().source())) return layout.carousel().slots();
        for (SpatialLayout.Panel panel : layout.panels().values()) {
            SpatialLayout.Content content = panel.content();
            if (!primary.equals(content.source())) continue;
            return content.type() == SpatialLayout.Content.Type.GRID ? content.rows() * content.columns() : content.rows();
        }
        return 1;
    }

    private void takeOff(SpatialContext ctx) {
        if (unequip == null || wearing == null || !wearing.test(ctx)) return;
        String refusal = unequip.apply(ctx);
        if (refusal != null) refuse(ctx.viewer(), refusal);
        ctx.refresh();
    }

    private void favourite(SpatialContext ctx) {
        String id = shown(ctx, primary);
        if (id == null || star == null) {
            refuse(ctx.viewer(), null);
            return;
        }
        star.accept(ctx.viewer(), id);
        ctx.refresh();
    }

    /** The next filter: the chosen offer stays chosen (it is the draft), the list opens on its page. */
    private void nextFilter(SpatialContext ctx) {
        List<String> ids = new ArrayList<>();
        if (categories != null) for (Category category : categories.apply(ctx)) ids.add(category.id());
        ctx.set(FILTER_KEY, Catalogs.nextFilter(current(ctx), ids, allFilter || categories == null, ALL,
                starred == null ? null : FAVOURITES));
        pageTo(ctx, ctx.selected(primary));
    }

    private void openCrate(SpatialContext ctx) {
        PluginCrates crate = crates == null ? null : crates.get();
        Player player = ctx.viewer();
        if (crate == null) {
            refuse(player, null);
            return;
        }
        ctx.close();
        // After the menu has deflated (170 ms): the crate's inventory would open over it otherwise.
        Tasks.of(menus.plugin()).runAtEntityLater(player, 5L, () -> {
            if (player.isOnline()) crate.open(player);
        }, () -> { });
    }

    private void refuse(Player player, @Nullable String line) {
        var file = menus.layout(menu.id());
        StageLook.play(player, file == null ? new SpatialTheme().errorSound() : file.get().theme().errorSound());
        if (line != null && !line.isBlank()) player.sendMessage(Text.component(line));
    }
}
