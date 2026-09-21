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
import net.exylia.lib.util.crate.CrateMessages;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Two screens: one that asks how many crates, and one that opens them.
 *
 * <p>Every reel is its own: its own faces, its own prize and its own finishing
 * line a little later than the last, so four of them land one after another
 * instead of snapping still together. The keys are spent and the prizes drawn
 * before the first frame, but nothing is handed over until a reel stops on the
 * line, so what the player is given arrives with the animation rather than
 * ahead of it.
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
    private final Function<Player, ItemStack> keyBack;
    private final BiConsumer<Player, ItemStack> giveItem;

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

    /** One player's reels and the timer drawing them. */
    private static final class Spin<T> {
        final Player player;
        final List<Reel<T>> reels;
        volatile @Nullable TaskHandle timer;

        Spin(Player player, List<Reel<T>> reels) {
            this.player = player;
            this.reels = reels;
        }
    }

    /**
     * @param isKey    whether an item is one of this crate's key items
     * @param keyBack  a fresh key item, for a paid key a crate turned out unable to use
     * @param giveItem hands an item over under the plugin's overflow policy
     */
    public CrateScreens(@NotNull Plugin plugin, @NotNull Prizes<T> prizes, @NotNull CrateStore store,
                        @NotNull Supplier<CrateSettings> settings, @NotNull Supplier<CrateMessages> messages,
                        @NotNull PluginMenus menus, @NotNull String menuId, @NotNull String openingId,
                        @NotNull Predicate<ItemStack> isKey, @NotNull Function<Player, ItemStack> keyBack,
                        @NotNull BiConsumer<Player, ItemStack> giveItem) {
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
    }

    /**
     * Settles up every reel still falling when the player left: unlocked on
     * their row, and any token kept until their next join.
     *
     * <p>Before the store forgets their row — hence the priority — because an
     * unlock written after that is written to nothing.
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
        for (Reel<T> reel : spin.reels) {
            if (reel.abandon()) prizes.awardLater(spin.player, reel.prize());
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
        List<Integer> columns = Reel.columns(mostAtOnce());

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

    private int mostAtOnce() {
        return Math.min(Reel.COLUMNS, Math.max(1, settings.get().maxAtOnce()));
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
            whenLoaded(player, () -> spin(player, 1, true));
            return;
        }
        open(player);
    }

    /** Spends keys from the account and opens the screen the reels run on. */
    public void spin(@NotNull Player player, int requested) {
        whenLoaded(player, () -> spin(player, requested, false));
    }

    /**
     * Everything that can refuse does so before a window is opened, and each
     * refusal says which one it was.
     *
     * @param held whether the keys are items in the hand rather than the number on the account
     */
    private void spin(Player player, int requested, boolean held) {
        CrateMessages lines = messages.get();
        int amount = Math.min(mostAtOnce(), Math.max(1, requested));
        UUID uuid = player.getUniqueId();

        if (spinning.asMap().putIfAbsent(uuid, Boolean.TRUE) != null) {
            say(player, lines.busy());
            return;
        }

        int keys = held ? heldKeys(player) : keysOf(player);
        if (keys < amount) {
            spinning.invalidate(uuid);
            if (!lines.noKeys().isBlank()) {
                Text.from(plugin, lines.noKeys()).with("%amount%", amount).with("%keys%", keys).send(player);
            }
            return;
        }

        // Every key is spent before any reel is drawn, so the window cannot be
        // closed between two of them and pay for half a spin.
        List<T> bought = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++) {
            Prizes.Draw<T> draw;
            if (held) {
                // The hand was counted a moment ago on this same thread, so only
                // a race gets here; a spin that paid for nothing opens nothing.
                if (!takeHeldKey(player)) break;
                draw = prizes.drawPaid();
                if (!draw.isPrize()) giveItem.accept(player, keyBack.apply(player));
            } else {
                draw = prizes.draw(player);
            }
            if (!draw.isPrize()) {
                // Only an empty or disabled crate, and then on the first one:
                // whatever came before it is already paid for.
                if (bought.isEmpty()) {
                    spinning.invalidate(uuid);
                    say(player, draw.status() == Prizes.Status.EMPTY ? lines.empty() : lines.disabled());
                    return;
                }
                break;
            }
            bought.add(draw.prize());
        }
        if (bought.isEmpty()) {
            spinning.invalidate(uuid);
            return;
        }

        menus.definition(openingId).ifPresentOrElse(
                // A tick later, because this runs inside the click on the button:
                // a window opened while the one clicked is still being handled is
                // the click that closes both.
                definition -> tasks.runAtEntityLater(player, 1, () -> run(player, definition, bought)),
                () -> {
                    spinning.invalidate(uuid);
                    debug.warn("The crate opening menu '" + openingId + "' is not loaded");
                    // No screen to land on, so the keys pay out at once.
                    bought.forEach(prize -> announce(player, prizes.award(player, prize)));
                });
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

    private void run(Player player, UiDefinition definition, List<T> bought) {
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

        UiSession session = menus.openNow(player, definition, context,
                Map.of(REELS, cells(reels, 0),
                        AGAIN, List.of(UiEntry.row().template("waiting").build())));
        animate(session, reels);
    }

    private @Nullable T any(List<T> faces) {
        return faces.isEmpty() ? null
                : faces.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(faces.size()));
    }

    /**
     * Runs the reels until the last one lands, on one timer: they are drawn into
     * the same section, and two timers writing it would undo each other's column.
     */
    private void animate(UiSession session, List<Reel<T>> reels) {
        Player player = session.viewer();
        long[] elapsed = {0};
        int[] drawn = {-1};
        int[] beats = {0};
        Spin<T> spin = new Spin<>(player, reels);
        running.put(player.getUniqueId(), spin);

        spin.timer = tasks.runAtEntityTimer(player, 1, 1, (Consumer<TaskHandle>) handle -> {
            // The window may be gone, closed or replaced. The prizes still land
            // on their own clock: the reels simply stop being drawn.
            boolean gone = menus.session(player).map(open -> open != session).orElse(true);
            boolean done = reels.stream().allMatch(reel -> reel.landed(elapsed[0]));

            // Paid before the frame is drawn, so the cell that lands is already
            // the winner or the duplicate it turned out to be. Each reel is
            // settled the tick it stops: four crates are four prizes.
            for (Reel<T> reel : reels) {
                if (!reel.landed(elapsed[0])) continue;
                Prizes.Outcome<T> outcome = reel.settle(prize -> prizes.award(player, prize));
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
                    session.entries(REELS, cells(reels, elapsed[0]));
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
        Text.from(plugin, line)
                .withFormatted("%reward%", prizes.catalogue().name(prize))
                .withFormatted("%tier%", tierName(outcome.tierId()))
                .with("%refund%", outcome.refunded())
                .send(player);
    }

    // ------------------------------------------------------------------
    // Drawing the reels
    // ------------------------------------------------------------------

    /**
     * Every cell of the grid, column by column, the columns no reel uses
     * included: rows fill a section's slots in order, so three reels that only
     * sent three columns would be drawn in the first three.
     */
    private List<UiEntry> cells(List<Reel<T>> reels, long elapsed) {
        List<UiEntry> rows = new ArrayList<>(Reel.COLUMNS * Reel.ROWS);
        for (int column = Reel.FIRST_COLUMN; column <= Reel.LAST_COLUMN; column++) {
            Reel<T> reel = null;
            for (Reel<T> candidate : reels) {
                if (candidate.column() == column) reel = candidate;
            }
            for (int row = 0; row < Reel.ROWS; row++) {
                if (reel != null) {
                    rows.add(cell(reel, row, elapsed));
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
    private UiEntry cell(Reel<T> reel, int row, long elapsed) {
        T face = reel.faceAt(elapsed, row);
        if (face == null) return blank();

        Prizes.Outcome<T> outcome = reel.outcome();
        UiEntry.Builder entry = face(face).with("refund", outcome == null ? 0 : outcome.refunded());
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

    /** Everything a template can say about one reward, its rarity included. */
    private UiEntry.Builder face(T reward) {
        CrateCatalogue<T> catalogue = prizes.catalogue();
        TierTable tiers = prizes.tiers();
        String tierId = tiers.resolveId(catalogue.tier(reward));
        CrateTier tier = tiers.get(tierId);
        return UiEntry.of(reward)
                .with("reward_id", prizes.idOf(reward))
                .withFormatted("reward_name", catalogue.name(reward))
                .with("reward_material", catalogue.icon(reward))
                .withFormatted("reward_description", catalogue.description(reward))
                .with("reward_tier_id", tierId)
                .withFormatted("reward_tier", tier.color() + tier.name())
                .withFormatted("reward_tier_color", tier.color());
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
        return tier.color() + tier.name();
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
