package net.exylia.lib.util.crate.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.block.BlockButton;
import net.exylia.lib.block.BlockClick;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.effect.EffectConfig;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.effect.PluginEffects;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiSession;
import net.exylia.lib.util.crate.CrateCatalogue;
import net.exylia.lib.item.Item;
import net.exylia.lib.item.Items;
import net.exylia.lib.spatial.SpatialPreview;
import net.exylia.lib.spatial.crate.CrateFace;
import net.exylia.lib.spatial.crate.CrateReveal;
import net.exylia.lib.spatial.crate.CrateScene;
import net.exylia.lib.spatial.crate.CrateShow;
import net.exylia.lib.util.crate.CrateMessages;
import net.exylia.lib.util.crate.CrateOpening;
import net.exylia.lib.util.crate.CrateSettings;
import net.exylia.lib.util.crate.CrateTier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Two screens: one that asks how many crates, and one that opens them.
 *
 * <p>Every reel is its own: its own faces, its own prize and its own finishing
 * line a little later than the last, so four of them land one after another
 * instead of snapping still together. The keys are spent and the prizes written
 * before the first frame — nothing is shown for an opening the database did
 * not take — and a reel's token item is handed over as it stops on the line.
 *
 * <p>Closing the window changes none of that — the reels stop being drawn and
 * the prizes still land on their own clock. Leaving the server ends the timer,
 * so what was still in the air is settled up on the way out.
 *
 * @param <T> the plugin's reward type
 */
public final class CrateScreens<T> implements Listener {

    /** The question's list of choices. */
    static final String AMOUNTS = "amounts";

    /** Every cell of every reel, read down one column before starting the next. */
    static final String REELS = "reels";

    /** The way back, which is nothing at all until the last reel has landed. */
    static final String AGAIN = "again";

    private final Plugin plugin;
    private final Predicate<Player> access;
    private final ToIntFunction<Player> atOnce;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final PluginEffects effects;
    private final Prizes<T> prizes;
    private final CrateStore store;
    private final Supplier<CrateSettings> settings;
    private final Supplier<CrateMessages> messages;
    private final PluginMenus menus;
    private final String menuId;
    private final String openingId;
    private final Predicate<ItemStack> isKey;
    private final Function<@Nullable Player, ItemStack> keyBack;
    private final BiConsumer<Player, ItemStack> giveItem;
    private final BiConsumer<UUID, ItemStack> deliverItem;

    /**
     * Who has reels running.
     *
     * <p>Expiring rather than a plain set: the entry is removed when the last
     * reel stops, but a stuck entry would lock somebody out of the crate for
     * good. A spin is seconds; a minute is a wide margin.
     */
    private final Cache<UUID, Boolean> spinning = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    /** The reels each player has in the air, so a spin that ends early can still be settled up. */
    private final Map<UUID, Spin<T>> running = new ConcurrentHashMap<>();

    /** One player's reels, the opening they show, and the timer drawing them. */
    private static final class Spin<T> {
        final Player player;
        final List<Reel<T>> reels;
        final Prizes.Ticket<T> ticket;
        final List<Prizes.Outcome<T>> outcomes;
        volatile @Nullable TaskHandle timer;

        Spin(Player player, List<Reel<T>> reels, Prizes.Ticket<T> ticket, List<Prizes.Outcome<T>> outcomes) {
            this.player = player;
            this.reels = reels;
            this.ticket = ticket;
            this.outcomes = outcomes;
        }
    }

    /**
     * @param isKey    whether an item is one of this crate's key items
     * @param keyBack     a fresh key item, for a paid key an opening turned out unable to use; drawn for
     *                    nobody in particular when given {@code null}
     * @param giveItem    hands an item over under the plugin's overflow policy
     * @param deliverItem hands an item over to somebody here or not
     * @param access   whether a player may open the crate at all, telling them why not
     * @param atOnce   the most crates a player may open at once, before the reels' cap
     */
    public CrateScreens(@NotNull Plugin plugin, @NotNull Prizes<T> prizes, @NotNull CrateStore store,
                        @NotNull Supplier<CrateSettings> settings, @NotNull Supplier<CrateMessages> messages,
                        @NotNull PluginMenus menus, @NotNull String menuId, @NotNull String openingId,
                        @NotNull Predicate<ItemStack> isKey, @NotNull Function<@Nullable Player, ItemStack> keyBack,
                        @NotNull BiConsumer<Player, ItemStack> giveItem, @NotNull BiConsumer<UUID, ItemStack> deliverItem,
                        @NotNull Predicate<Player> access, @NotNull ToIntFunction<Player> atOnce) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
        this.effects = Effects.of(plugin);
        this.prizes = prizes;
        this.store = store;
        this.settings = settings;
        this.messages = messages;
        this.menus = menus;
        this.menuId = menuId;
        this.openingId = openingId;
        this.isKey = isKey;
        this.keyBack = keyBack;
        this.giveItem = giveItem;
        this.deliverItem = deliverItem;
        this.access = access;
        this.atOnce = atOnce;
    }

    /**
     * Settles up every reel still falling when the player left. What they won
     * was written with the opening; only the tokens are left, kept until their
     * next join.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        settle(event.getPlayer().getUniqueId());
    }

    /** Settles up every spin in the air, the way a quit does, and stops drawing them. */
    public void settleAll() {
        for (UUID uuid : Map.copyOf(running).keySet()) settle(uuid);
    }

    private void settle(UUID uuid) {
        Spin<T> spin = running.remove(uuid);
        spinning.invalidate(uuid);
        if (spin == null) return;
        TaskHandle timer = spin.timer;
        if (timer != null) timer.cancel();
        for (int i = 0; i < spin.reels.size(); i++) {
            // An unrevealed token stays in the pending rewards the opening wrote it to: the next join hands it over.
            spin.reels.get(i).abandon();
        }
    }

    // ------------------------------------------------------------------
    // How many?
    // ------------------------------------------------------------------

    /** Opens the question, reading the player's row first when it is not in memory. */
    public void open(@NotNull Player player) {
        if (!prizes.enabled()) {
            say(player, messages.get().disabled());
            return;
        }
        if (!access.test(player)) return;
        whenLoaded(player, () -> menus.definition(menuId).ifPresentOrElse(definition -> {
            Map<String, Object> context = new HashMap<>();
            putStatus(context::put, player);
            menus.openNow(player, definition, context, Map.of(AMOUNTS, amounts(player)));
        }, () -> debug.warn("The crate menu '" + menuId + "' is not loaded")));
    }

    /**
     * One choice per number of crates the player may open at once, laid over
     * the same seven columns the reels use and spread the same way, so each
     * choice sits exactly where the reels it opens will fall.
     */
    private List<UiEntry> amounts(Player player) {
        int keys = keysOf(player);
        List<Integer> columns = Reel.columns(mostAtOnce(player));

        List<UiEntry> rows = new ArrayList<>(Reel.COLUMNS);
        for (int column = Reel.FIRST_COLUMN; column <= Reel.LAST_COLUMN; column++) {
            int choice = columns.indexOf(column) + 1;
            if (choice == 0) {
                rows.add(blank());
                continue;
            }
            rows.add(UiEntry.of(choice)
                    .with("amount", choice)
                    .with("keys", keys)
                    .with("missing", Math.max(0, choice - keys))
                    .template(choice <= keys ? null : "empty")
                    .build());
        }
        return rows;
    }

    private int mostAtOnce(Player player) {
        return Math.min(Reel.COLUMNS, Math.max(1, atOnce.applyAsInt(player)));
    }

    // ------------------------------------------------------------------
    // Opening them
    // ------------------------------------------------------------------

    /**
     * What a click on a crate block does.
     *
     * <p>Right clicking with a key item in the hand opens one crate on the spot,
     * because that is what holding a key means. Anything else is the question
     * and the keys on the account. Only the right button spends a held key: a
     * left click is how a player checks what a block is.
     */
    public void click(@NotNull BlockClick click) {
        Player player = click.player();
        if (!prizes.enabled()) {
            say(player, messages.get().disabled());
            return;
        }
        if (click.button() == BlockButton.RIGHT && isKey.test(player.getInventory().getItemInMainHand())) {
            whenLoaded(player, () -> spin(player, 1, true, null));
            return;
        }
        open(player);
    }

    /** Spends keys from the account and opens the screen the reels run on. */
    public void spin(@NotNull Player player, int requested) {
        whenLoaded(player, () -> spin(player, requested, false, null));
    }

    /**
     * One crate in the 3D scene, whatever {@code opening} says: a spatial menu's crate button. Keys on
     * the account first, else a key item in the hand.
     *
     * @param back runs on the player's thread once the scene is over and every prize handed over
     * @return {@code false} when it was refused at once (no key, busy, empty...), the line already sent
     */
    public boolean spinSpatial(@NotNull Player player, @NotNull Runnable back) {
        if (!prizes.enabled()) {
            say(player, messages.get().disabled());
            return false;
        }
        if (!store.isLoaded(player.getUniqueId())) {
            whenLoaded(player, () -> spin(player, 1, heldKeys(player) > 0 && keysOf(player) < 1, back));
            return true;
        }
        return spin(player, 1, heldKeys(player) > 0 && keysOf(player) < 1, back);
    }

    /** Whether an opening plays as the 3D scene: forced from a spatial menu, or as the settings say. */
    static boolean spatial(@NotNull CrateOpening configured, boolean forced, boolean available) {
        return available && (forced || configured == CrateOpening.SPATIAL);
    }

    /**
     * Everything that can refuse at once does so before anything is written,
     * and each refusal says which one it was. Then the opening is written off
     * this thread and shown once the database took it: nothing is shown, and
     * nothing handed over, for an opening that was not written.
     *
     * @param held whether the keys are items in the hand rather than the number on the account
     * @param back  non-null: the 3D scene whatever the settings say, and this runs once it is over
     * @return whether an opening is on its way
     */
    private boolean spin(Player player, int requested, boolean held, @Nullable Runnable back) {
        if (!access.test(player)) return false;
        CrateMessages lines = messages.get();
        if (!prizes.enabled()) {
            say(player, lines.disabled());
            return false;
        }
        int amount = Math.min(mostAtOnce(player), Math.max(1, requested));
        UUID uuid = player.getUniqueId();

        if (spinning.asMap().putIfAbsent(uuid, Boolean.TRUE) != null) {
            say(player, lines.busy());
            return false;
        }

        // Only a first answer from memory: the write below checks the keys again on the row itself.
        int keys = held ? heldKeys(player) : keysOf(player);
        if (keys < amount) {
            spinning.invalidate(uuid);
            if (!held) store.refresh(uuid);
            if (!lines.noKeys().isBlank()) {
                Text.from(plugin, lines.noKeys()).with("%amount%", amount).with("%keys%", keys).send(player);
            }
            return false;
        }

        // Rolled before anything is taken: a crate with nothing in it must not charge for the disappointment.
        List<T> rolled = prizes.rollAll(amount);
        if (rolled == null) {
            spinning.invalidate(uuid);
            say(player, lines.empty());
            return false;
        }
        int paidWithItems = 0;
        if (held) {
            // Read again rather than trusted from the count; a spin that paid for nothing opens nothing.
            while (paidWithItems < rolled.size() && takeHeldKey(player)) paidWithItems++;
            if (paidWithItems == 0) {
                spinning.invalidate(uuid);
                return false;
            }
            rolled = new ArrayList<>(rolled.subList(0, paidWithItems));
        }

        Prizes.Ticket<T> ticket = prizes.ticket(player, held ? 0 : rolled.size(), rolled);
        int items = paidWithItems;
        AtomicBoolean late = new AtomicBoolean();
        prizes.open(uuid, ticket, () -> {
            late.set(true);
            try {
                tasks.runAtEntity(player, () -> say(player, lines.failed()));
            } catch (RuntimeException stopped) {
                // Nobody left to tell.
            }
        }).whenComplete((opened, failure) -> {
            try {
                tasks.runAtEntity(player, () -> landed(player, ticket, opened, failure, held, items, late.get(), back),
                        () -> landedAway(uuid, ticket, opened, failure, items));
            } catch (RuntimeException stopped) {
                landedAway(uuid, ticket, opened, failure, items);
            }
        });
        return true;
    }

    /** The database answered an opening, and the player is still here. On their thread. */
    private void landed(Player player, Prizes.Ticket<T> ticket, Prizes.@Nullable Opened<T> opened,
                        @Nullable Throwable failure, boolean held, int items, boolean late, @Nullable Runnable back) {
        UUID uuid = player.getUniqueId();
        if (failure != null || opened == null) {
            // The plugin stopped before the database answered. Whatever it settles on is kept in
            // the row's journal: tokens are handed over on the next join, nothing else is owed.
            spinning.invalidate(uuid);
            debug.warn("Crate: an opening for " + player.getName() + " was still unanswered when the crate stopped");
            return;
        }
        switch (opened.result()) {
            case NO_KEYS -> {
                spinning.invalidate(uuid);
                CrateMessages lines = messages.get();
                if (!lines.noKeys().isBlank()) {
                    Text.from(plugin, lines.noKeys()).with("%amount%", ticket.prizes().size())
                            .with("%keys%", keysOf(player)).send(player);
                }
                if (back != null) back.run();
            }
            case ABORTED -> {
                // Aborted for good, so the key items it took can go back: it will never land.
                spinning.invalidate(uuid);
                for (int i = 0; i < items; i++) giveItem.accept(player, keyBack.apply(player));
                if (back != null) back.run();
            }
            case OPENED -> {
                if (late) {
                    // They were told it failed; it had landed after all. No reels now, only what came out.
                    spinning.invalidate(uuid);
                    for (int i = 0; i < opened.outcomes().size(); i++) {
                        announce(player, reveal(player, ticket, opened.outcomes(), i));
                    }
                    if (back != null) back.run();
                    return;
                }
                show(player, ticket, opened.outcomes(), held, back);
            }
        }
    }

    /** The database answered an opening for a player who left meanwhile. On any thread. */
    private void landedAway(UUID uuid, Prizes.Ticket<T> ticket, Prizes.@Nullable Opened<T> opened,
                            @Nullable Throwable failure, int items) {
        spinning.invalidate(uuid);
        if (failure != null || opened == null) return;
        if (opened.result() == Prizes.Result.ABORTED) {
            for (int i = 0; i < items; i++) deliverItem.accept(uuid, keyBack.apply(null));
            return;
        }
        // An OPENED one wrote its tokens to the pending rewards, handed over on the next join.
    }

    /** Puts a written opening on screen: the 3D scene, the screen, or the reels. */
    private void show(Player player, Prizes.Ticket<T> ticket, List<Prizes.Outcome<T>> outcomes, boolean held,
                      @Nullable Runnable back) {
        UUID uuid = player.getUniqueId();
        boolean screen = back == null && settings.get().opening() == CrateOpening.SCREEN
                && net.exylia.lib.spatial.crate.CrateScreen.of(plugin).available(player);
        if (screen || spatial(settings.get().opening(), back != null, CrateScene.of(plugin).available(player))) {
            // Held from now, so a quit in the tick before the scene opens still pays them out.
            List<Reel<T>> reels = new ArrayList<>(ticket.prizes().size());
            for (T prize : ticket.prizes()) reels.add(Reel.of(0, prize, 1, () -> null));
            Spin<T> spin = new Spin<>(player, reels, ticket, outcomes);
            running.put(uuid, spin);
            // A tick later, out of the click on the button that asked for it.
            tasks.runAtEntityLater(player, 1, () -> runSpatial(player, spin, held, back, screen));
            return;
        }
        inventory(player, ticket, outcomes);
    }

    /** The reels in the opening menu, or the prizes at once when that menu is not loaded. */
    private void inventory(Player player, Prizes.Ticket<T> ticket, List<Prizes.Outcome<T>> outcomes) {
        UUID uuid = player.getUniqueId();
        menus.definition(openingId).ifPresentOrElse(
                // A tick later, because this runs inside the click on the button:
                // a window opened while the one clicked is still being handled is
                // the click that closes both.
                // Already written: a player gone before that tick is paid like a
                // quit mid-spin, since onQuit has no spin of theirs to settle.
                definition -> tasks.runAtEntityLater(player, 1, () -> run(player, definition, ticket, outcomes), () -> {
                    spinning.invalidate(uuid);
                }),
                () -> {
                    spinning.invalidate(uuid);
                    debug.warn("The crate opening menu '" + openingId + "' is not loaded");
                    // No screen to land on, so the prizes are told at once.
                    for (int i = 0; i < outcomes.size(); i++) announce(player, reveal(player, ticket, outcomes, i));
                });
    }

    /**
     * Reveals one reel: what it came to was written with the opening, so this only
     * hands over its token, when it has one.
     */
    private Prizes.Outcome<T> reveal(Player player, Prizes.Ticket<T> ticket, List<Prizes.Outcome<T>> outcomes,
                                     int index) {
        Prizes.Outcome<T> outcome = outcomes.get(index);
        if (outcome.token()) handToken(player, ticket.id(), index, ticket.tokens().get(index));
        return outcome;
    }

    /**
     * Hands one reel's token over to a player who is here: taken off the pending
     * rewards first, where the opening wrote it, so it is handed over once
     * whoever else tries; given on their thread, or queued again for their next
     * join when they left meanwhile. Not taken, it stays pending, and the next
     * join hands it over.
     */
    private void handToken(Player player, String operation, int index, Prizes.@Nullable Token token) {
        if (token == null) return;
        UUID uuid = player.getUniqueId();
        prizes.takeDelivery(operation, index).thenAccept(won -> {
            if (!won) return;
            tasks.runAtEntity(player, () -> token.give(player), () -> token.keep(uuid));
        }).exceptionally(failure -> {
            debug.warn("Crate: a token for " + player.getName() + " is still pending, handed over on their next join: "
                    + failure.getMessage());
            return null;
        });
    }

    /**
     * The opening in the world ({@link CrateScene}), or on a screen when {@code screen}. The prizes are already
     * written: each reel is only the guard that hands its token over once, when the scene reveals it or when it
     * ends early. A quit is paid by {@link #onQuit}, as with the inventory reels.
     */
    private void runSpatial(Player player, Spin<T> spin, boolean held, @Nullable Runnable back, boolean screen) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || running.get(uuid) != spin) return;
        List<Reel<T>> reels = spin.reels;
        List<T> bought = spin.ticket.prizes();

        CrateCatalogue<T> catalogue = prizes.catalogue();
        Map<T, CrateFace> drawn = new HashMap<>();
        Function<T, CrateFace> face = reward -> drawn.computeIfAbsent(reward, r -> face(r, player));
        List<CrateFace> faces = catalogue.all().stream().map(face).toList();
        List<CrateFace> shown = bought.stream().map(face).toList();

        CrateShow show = new CrateShow() {
            /** OPEN ANOTHER was pressed: the next scene goes back instead of this one. */
            boolean again;

            @Override
            public @NotNull List<CrateFace> faces() {
                return faces;
            }

            @Override
            public @NotNull List<CrateFace> prizes() {
                return shown;
            }

            @Override
            public @NotNull CrateReveal reveal(int index) {
                Reel<T> reel = reels.get(index);
                Prizes.Outcome<T> outcome = reel.settle(prize -> CrateScreens.this.reveal(player, spin.ticket,
                        spin.outcomes, index));
                if (outcome != null) {
                    effects.play(landing(outcome), player);
                    announce(player, outcome);
                }
                Prizes.Outcome<T> known = spin.outcomes.get(index);
                return known.status() != Prizes.Status.DUPLICATE ? CrateReveal.NEW
                        : new CrateReveal(true, known.refunded());
            }

            @Override
            public @Nullable SpatialPreview preview(int index) {
                return catalogue.preview(bought.get(index), player);
            }

            @Override
            public boolean previewVanishes() {
                return catalogue.previewVanishes();
            }

            @Override
            public boolean canOpenAnother() {
                return prizes.enabled() && (held ? heldKeys(player) : keysOf(player)) >= 1;
            }

            @Override
            public void openAnother() {
                again = true;
                tasks.runAtEntityLater(player, 1, () -> whenLoaded(player, () -> {
                    if (!spin(player, 1, held, back) && back != null) back.run();
                }));
            }

            @Override
            public void ended(boolean quit) {
                if (quit) return;
                running.remove(uuid, spin);
                spinning.invalidate(uuid);
                // Every prize is handed over by now. After the scene's 170 ms deflate, and only once
                // openAnother (called right after this) has had its say.
                if (back != null) tasks.runAtEntityLater(player, 5L, () -> {
                    if (!again && player.isOnline()) back.run();
                });
            }
        };
        boolean played = screen ? net.exylia.lib.spatial.crate.CrateScreen.of(plugin).play(player, show)
                : CrateScene.of(plugin).play(player, show);
        if (!played) {
            // Nothing could be drawn there (another plugin's 3D menu, no displays): the reels instead.
            running.remove(uuid, spin);
            inventory(player, spin.ticket, spin.outcomes);
        }
    }

    /** A reward as the 3D opening draws it: its real item, name and rarity. */
    private CrateFace face(T reward, Player viewer) {
        CrateCatalogue<T> catalogue = prizes.catalogue();
        ItemStack icon = catalogue.icon(reward, viewer);
        if (icon == null) {
            icon = Items.of(plugin).render(Item.of(catalogue.icon(reward)).build(), viewer);
        }
        TierTable tiers = prizes.tiers();
        String tierId = prizes.tierOf(reward);
        CrateTier tier = tiers.get(tierId);
        int rank = tiers.ids().indexOf(tiers.resolveId(tierId)) + 1;
        return new CrateFace(prizes.idOf(reward), icon, catalogue.name(reward), tier.color() + net.exylia.lib.text.Phrases.tr(tier.name()),
                tier.color(), rank, tiers.ids().size());
    }

    private int heldKeys(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        return isKey.test(hand) ? hand.getAmount() : 0;
    }

    /**
     * Takes one key out of the hand, read again rather than trusted from the
     * count: the stack may have moved, and a key taken from a slot that no
     * longer holds one is a crate opened for free.
     */
    private boolean takeHeldKey(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isKey.test(hand) || hand.getAmount() < 1) return false;
        if (hand.getAmount() == 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(hand.getAmount() - 1);
        }
        return true;
    }

    private void run(Player player, UiDefinition definition, Prizes.Ticket<T> ticket,
                     List<Prizes.Outcome<T>> outcomes) {
        List<T> bought = ticket.prizes();
        CrateSettings crate = settings.get();
        int base = Math.min(Reel.MAX_FRAMES, Math.max(1, crate.spinFrames()));
        // In faces rather than in seconds: every face a reel gains lands in the
        // fast run, where one face is exactly one tick, so the gap between two
        // landings is the number the owner wrote, to the tick.
        int stagger = (int) Math.round(Math.max(0, crate.staggerSeconds()) * 20);

        List<T> faces = List.copyOf(prizes.catalogue().all());
        List<Integer> columns = Reel.columns(bought.size());
        List<Reel<T>> reels = new ArrayList<>(bought.size());
        for (int i = 0; i < bought.size(); i++) {
            reels.add(Reel.of(columns.get(i), bought.get(i), base + i * stagger, () -> any(faces)));
        }

        Map<String, Object> context = new HashMap<>();
        context.put("amount", bought.size());
        putStatus(context::put, player);

        CrateFaces<T> drawn = new CrateFaces<>(prizes.catalogue(), prizes.tiers(), player);
        UiSession session = menus.openNow(player, definition, context,
                Map.of(REELS, cells(reels, 0, drawn),
                        AGAIN, List.of(UiEntry.row().template("waiting").build())));
        animate(session, reels, drawn, ticket, outcomes);
    }

    private @Nullable T any(List<T> faces) {
        return faces.isEmpty() ? null
                : faces.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(faces.size()));
    }

    /**
     * Runs the reels until the last one lands, on one timer: they are drawn into
     * the same section, and two timers writing it would undo each other's column.
     */
    private void animate(UiSession session, List<Reel<T>> reels, CrateFaces<T> faces, Prizes.Ticket<T> ticket,
                         List<Prizes.Outcome<T>> outcomes) {
        Player player = session.viewer();
        long[] elapsed = {0};
        int[] drawn = {-1};
        int[] beats = {0};
        Spin<T> spin = new Spin<>(player, reels, ticket, outcomes);
        running.put(player.getUniqueId(), spin);

        spin.timer = tasks.runAtEntityTimer(player, 1, 1, (Consumer<TaskHandle>) handle -> {
            // The window may be gone, closed or replaced. The prizes still land
            // on their own clock: the reels simply stop being drawn.
            boolean gone = menus.session(player).map(open -> open != session).orElse(true);
            boolean done = reels.stream().allMatch(reel -> reel.landed(elapsed[0]));

            // Settled before the frame is drawn, so the cell that lands is already
            // the winner or the duplicate it was written as. Each reel is
            // settled the tick it stops: four crates are four prizes.
            for (int i = 0; i < reels.size(); i++) {
                Reel<T> reel = reels.get(i);
                if (!reel.landed(elapsed[0])) continue;
                int index = i;
                Prizes.Outcome<T> outcome = reel.settle(prize -> reveal(player, ticket, outcomes, index));
                if (outcome == null) continue;
                effects.play(landing(outcome), player);
                announce(player, outcome);
            }

            if (!gone) {
                int position = 0;
                for (Reel<T> reel : reels) {
                    position = position * 31 + reel.frameAt(elapsed[0]);
                }
                // Only when something moved: at the slow end a frame lasts
                // nearly half a second, and redrawing it nine times is nine
                // windows of packets saying the same thing.
                if (position != drawn[0] || done) {
                    drawn[0] = position;
                    session.entries(REELS, cells(reels, elapsed[0], faces));
                    // Every other face: one sound each is twenty a second, a
                    // rattle rather than a reel, and the widening gap between
                    // clicks is what makes the slowdown audible.
                    if (!done && beats[0]++ % 2 == 0) {
                        effects.play(settings.get().onSpin(), player);
                    }
                }
            }

            if (!done) {
                elapsed[0]++;
                return;
            }
            handle.cancel();
            running.remove(player.getUniqueId(), spin);
            spinning.invalidate(player.getUniqueId());
            if (!gone) {
                // Only now: a way out offered mid-fall is a way to click past
                // the thing they are watching.
                session.entries(AGAIN, List.of(UiEntry.row().build()));
                putStatus(session::context, player);
                session.refresh();
            }
        });
    }

    private EffectConfig landing(Prizes.Outcome<T> outcome) {
        CrateSettings crate = settings.get();
        return outcome.status() == Prizes.Status.WON ? crate.onWin() : crate.onDuplicate();
    }

    private void announce(Player player, Prizes.Outcome<T> outcome) {
        T prize = outcome.prize();
        if (prize == null) return;
        CrateMessages lines = messages.get();
        String line = outcome.status() == Prizes.Status.DUPLICATE ? lines.duplicate() : lines.won();
        if (line.isBlank()) return;
        String name = prizes.catalogue().name(prize);
        Text text = Text.from(plugin, line)
                .withFormatted("%reward%", name)
                .withFormatted("%tier%", tierName(outcome.tierId()))
                .with("%refund%", outcome.refunded());
        // The name a plugin's own crate filled, so its owners' lines keep working.
        String alias = CrateFaces.alias(prizes.catalogue());
        if (alias != null) text = text.withFormatted('%' + alias + '%', name);
        text.send(player);
    }

    // ------------------------------------------------------------------
    // Drawing the reels
    // ------------------------------------------------------------------

    /**
     * Every cell of the grid, column by column, the columns no reel uses
     * included: rows fill a section's slots in order, so three reels that only
     * sent three columns would be drawn in the first three.
     */
    private List<UiEntry> cells(List<Reel<T>> reels, long elapsed, CrateFaces<T> faces) {
        List<UiEntry> rows = new ArrayList<>(Reel.COLUMNS * Reel.ROWS);
        for (int column = Reel.FIRST_COLUMN; column <= Reel.LAST_COLUMN; column++) {
            Reel<T> reel = null;
            for (Reel<T> candidate : reels) {
                if (candidate.column() == column) reel = candidate;
            }
            for (int row = 0; row < Reel.ROWS; row++) {
                if (reel != null) {
                    rows.add(cell(reel, row, elapsed, faces));
                } else {
                    // The landing line runs right across, reel or no reel: one
                    // crate on its own is then a lane on a track.
                    rows.add(row == Reel.WINNER_ROW ? lane() : blank());
                }
            }
        }
        return rows;
    }

    /**
     * One cell: a face going past, the prize, or a near miss. What a stopped reel
     * came within a slot of stays on screen, because half of what a crate is
     * worth is the legendary that went past the line.
     */
    private UiEntry cell(Reel<T> reel, int row, long elapsed, CrateFaces<T> faces) {
        T face = reel.faceAt(elapsed, row);
        if (face == null) return blank();

        Prizes.Outcome<T> outcome = reel.outcome();
        UiEntry.Builder entry = faces.face(face).with("refund", outcome == null ? 0 : outcome.refunded());
        if (outcome == null || !reel.landed(elapsed)) {
            return entry.template(row == Reel.WINNER_ROW ? "middle" : null).build();
        }
        if (row != Reel.WINNER_ROW) return entry.template("near").build();
        return entry.template(outcome.status() == Prizes.Status.DUPLICATE ? "duplicate" : "winner").build();
    }

    private static UiEntry blank() {
        return UiEntry.row().template("blank").build();
    }

    private static UiEntry lane() {
        return UiEntry.row().template("lane").build();
    }

    // ------------------------------------------------------------------
    // Small readings
    // ------------------------------------------------------------------

    /**
     * Redraws the question from what the player's row now says. Never the
     * reels: the frames own that window until the last one lands.
     */
    public void refresh(@NotNull Player player) {
        menus.session(player).ifPresent(session -> {
            if (!session.menuId().equals(menus.namespace() + ':' + menuId)) return;
            session.entries(AMOUNTS, amounts(player));
            putStatus(session::context, player);
            session.refresh();
        });
    }

    /** What the fixed items around the reels draw. */
    private void putStatus(BiConsumer<String, Object> into, Player player) {
        into.accept("keys", keysOf(player));
        into.accept("unlocked_count", ownedCount(player));
        into.accept("catalogue_count", prizes.catalogue().all().size());
    }

    /** How many rewards of the catalogue a player owns, unlocked or otherwise. */
    public int ownedCount(@NotNull Player player) {
        CrateRow row = store.row(player.getUniqueId());
        CrateCatalogue<T> catalogue = prizes.catalogue();
        int owned = 0;
        for (T reward : catalogue.all()) {
            if ((row != null && row.isUnlocked(prizes.idOf(reward))) || catalogue.ownsOtherwise(player, reward)) {
                owned++;
            }
        }
        return owned;
    }

    private int keysOf(Player player) {
        CrateRow row = store.row(player.getUniqueId());
        return row == null ? 0 : row.keys();
    }

    private String tierName(String tierId) {
        CrateTier tier = prizes.tiers().get(tierId);
        return tier.color() + net.exylia.lib.text.Phrases.tr(tier.name());
    }

    private void say(Player player, String line) {
        if (!line.isBlank()) Text.from(plugin, line).send(player);
    }

    /** Runs now when the row is in memory, and on the player's thread once it is otherwise. */
    private void whenLoaded(Player player, Runnable then) {
        UUID uuid = player.getUniqueId();
        if (store.isLoaded(uuid)) {
            then.run();
            return;
        }
        // Somebody who already left would be given a row nobody ever forgets.
        if (!player.isOnline()) return;
        store.open(uuid).thenRun(() -> tasks.runAtEntity(player, () -> {
            if (store.isLoaded(uuid)) then.run();
        }));
    }
}
