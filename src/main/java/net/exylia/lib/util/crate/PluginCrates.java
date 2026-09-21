package net.exylia.lib.util.crate;

import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.item.Appearance;
import net.exylia.lib.item.Item;
import net.exylia.lib.item.ItemValues;
import net.exylia.lib.item.Items;
import net.exylia.lib.item.PluginItems;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.util.crate.internal.BoundBlocks;
import net.exylia.lib.util.crate.internal.CrateRow;
import net.exylia.lib.util.crate.internal.CrateScreens;
import net.exylia.lib.util.crate.internal.CrateStore;
import net.exylia.lib.util.crate.internal.Prizes;
import net.exylia.lib.util.crate.internal.TierTable;
import net.exylia.lib.util.reward.OverflowPolicy;
import net.exylia.lib.util.reward.PendingRewards;
import net.exylia.lib.util.reward.PluginRewards;
import net.exylia.lib.util.reward.RewardEntry;
import net.exylia.lib.util.reward.Rewards;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One plugin's crate.
 *
 * <pre>{@code
 * PluginCrates crates = Crates.of(this).start(catalogue,
 *         () -> config.get().crate(), () -> messages.get().crate(),
 *         bound -> config.update(c -> c.withCrateBlocks(bound)),
 *         menus, actions, "crate", "crate_open");
 * }</pre>
 *
 * <p>Nothing works until {@link #start}, except {@link #isKey}. After that the
 * settings and the catalogue are read afresh on every opening; only the bound
 * blocks need {@link #rebuild} after a reload.
 *
 * <h2>Threads</h2>
 * The cached reads ({@link #keys}, {@link #owns}, {@link #unlocked}) are safe
 * from any thread. Everything that touches a player's inventory or opens a
 * window ({@link #open}, {@link #spin}, {@link #keyItem}, {@link #giveKeyItems})
 * must be called on the thread that owns that player. The edits answer a
 * future, completed on whichever thread finished the work.
 *
 * @since 1.189.0
 */
public final class PluginCrates {

    /** The value that marks an item as one of this plugin's crate keys. */
    private static final String KEY_TAG = "crate_key";

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Debug debug;
    private final ItemValues values;
    private final List<Consumer<UUID>> listeners = new CopyOnWriteArrayList<>();
    private volatile @Nullable Running<?> running;

    /** Everything a started crate holds, dropped whole by {@link #stop}. */
    private record Running<T>(Supplier<CrateSettings> settings, Supplier<CrateMessages> messages,
                              CrateStore store, Prizes<T> prizes, CrateScreens<T> screens,
                              BoundBlocks blocks, PluginActions actions, PluginRewards rewards) {
    }

    PluginCrates(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
        this.debug = Debug.of(plugin);
        this.values = Items.of(plugin).values();
    }

    /**
     * Starts the crate: registers {@code <namespace>:crate} and
     * {@code <namespace>:crate_open <amount>}, binds the blocks the settings
     * name, and reads the keys of everybody online and everybody who joins.
     *
     * <p>Calling it again stops the running crate first, paying out what was in
     * the air, and starts over.
     *
     * <p>Rewards are handed over through the plugin's {@link PluginRewards},
     * which this sets to {@link OverflowPolicy#QUEUE} into
     * {@link PendingRewards#database the plugin's pending table}, claimed on
     * join: a token nobody had room for, or won by somebody who left, waits
     * there rather than being dropped.
     *
     * @param catalogue   what the crate can land on
     * @param settings    how it behaves, read afresh on every opening
     * @param messages    what it says, read afresh on every line
     * @param saveBlocks  writes the list of bound blocks when it changes; expected to
     *                    update what {@code settings} returns
     * @param menus       the plugin's menus, which the two screens are loaded into
     * @param actions     the namespace the screens' buttons are written against
     * @param menu        the question's menu id, such as {@code crate}
     * @param openingMenu the reels' menu id, such as {@code crate_open}
     * @return this
     */
    public synchronized <T> @NotNull PluginCrates start(@NotNull CrateCatalogue<T> catalogue,
                                                        @NotNull Supplier<CrateSettings> settings,
                                                        @NotNull Supplier<CrateMessages> messages,
                                                        @NotNull Consumer<List<String>> saveBlocks,
                                                        @NotNull PluginMenus menus,
                                                        @NotNull PluginActions actions,
                                                        @NotNull String menu, @NotNull String openingMenu) {
        stop();

        PluginItems items = Items.of(plugin);
        // A key is drawn as something placeable, and a right click at a wall
        // would put it there as a block: paid for, gone, and not a key any more.
        items.inert(KEY_TAG);
        PluginRewards rewards = Rewards.of(plugin)
                .overflow(OverflowPolicy.QUEUE)
                .pending(PendingRewards.database(plugin))
                .claimOnJoin((viewer, delivery) -> {
                    if (delivery.given() <= 0) return;
                    say(viewer, messages.get().rewardsClaimed(), delivery.given());
                });

        CrateStore store = new CrateStore(plugin, () -> settings.get().startKeys(), this::changed);
        Prizes<T> prizes = new Prizes<>(catalogue, store, settings, (prize, player) -> {
            ItemStack token = catalogue.token(prize, player);
            if (token == null) return null;
            RewardEntry entry = RewardEntry.item(Rewards.snapshot(token)).build();
            return new Prizes.Token() {
                @Override
                public void give(@NotNull Player player) {
                    rewards.give(player, List.of(entry));
                }

                @Override
                public boolean keep(@NotNull UUID player) {
                    return rewards.giveLater(player, List.of(entry));
                }
            };
        });
        CrateScreens<T> screens = new CrateScreens<>(plugin, prizes, store, settings, messages, menus,
                menu, openingMenu, this::isKey, this::keyItem, rewards::giveItem);
        BoundBlocks blocks = new BoundBlocks(plugin, () -> settings.get().blocks(), saveBlocks, screens::click);

        var events = plugin.getServer().getPluginManager();
        events.registerEvents(store, plugin);
        events.registerEvents(screens, plugin);
        events.registerEvents(blocks, plugin);
        actions.registerSync("crate", (context, arguments) -> {
            screens.open(context.player());
            return ActionResult.success();
        });
        actions.registerSync("crate_open", (context, arguments) -> {
            screens.spin(context.player(), arguments.integer(0, 1));
            return ActionResult.success();
        });

        running = new Running<>(settings, messages, store, prizes, screens, blocks, actions, rewards);
        blocks.rebuild();
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            store.open(online.getUniqueId());
        }
        return this;
    }

    /** Registers the bound blocks again from the settings, which a reload calls. */
    public void rebuild() {
        Running<?> current = running;
        if (current != null) current.blocks().rebuild();
    }

    /**
     * Stops the crate until the next {@link #start}. The library calls it when
     * the plugin is disabled.
     *
     * <p>Reels still in the air are paid out the way a quit pays them: unlocked
     * on the row, tokens kept until the player's next join. The actions and the
     * blocks registered by {@link #start} are taken down; the plugin's others
     * are left alone.
     */
    public synchronized void stop() {
        Running<?> current = running;
        if (current == null) return;
        // First, so nothing paid out below is told to a plugin on its way out.
        running = null;
        current.screens().settleAll();
        HandlerList.unregisterAll(current.store());
        HandlerList.unregisterAll(current.screens());
        current.blocks().shutdown();
        current.actions().unregister("crate");
        current.actions().unregister("crate_open");
        current.store().closeAll();
    }

    /**
     * Runs after a player's keys or unlocks change, so the plugin can redraw
     * what shows them. Called only for players online here, on their own
     * thread. Listeners stay until the plugin is disabled.
     *
     * @param listener told the player's id
     * @return this
     */
    public @NotNull PluginCrates onChange(@NotNull Consumer<UUID> listener) {
        listeners.add(listener);
        return this;
    }

    // ------------------------------------------------------------------
    // What a player has
    // ------------------------------------------------------------------

    /**
     * Reads a player's row, for a plugin that must know what they own before it
     * acts, such as one that takes off what they no longer own on join.
     *
     * @param player who, online here
     * @return done once their row is in memory; at once for a player who already left
     */
    public @NotNull CompletableFuture<Void> load(@NotNull Player player) {
        CrateStore store = require().store();
        // A row opened for somebody who left would never be forgotten.
        if (!player.isOnline()) return CompletableFuture.completedFuture(null);
        return store.open(player.getUniqueId());
    }

    /** Whether an online player's row is in memory yet. */
    public boolean isLoaded(@NotNull UUID player) {
        Running<?> current = running;
        return current != null && current.store().isLoaded(player);
    }

    /**
     * Whether a player unlocked a reward, from memory.
     *
     * @return {@code false} too for a player whose row is not in memory
     */
    public boolean owns(@NotNull UUID player, @NotNull String rewardId) {
        CrateRow row = row(player);
        return row != null && row.isUnlocked(TierTable.normalise(rewardId));
    }

    /**
     * Every reward id a player unlocked, in the order they came, from memory.
     * Ids the catalogue no longer declares are kept: a typo in a reload must not
     * take away what somebody paid a key for.
     *
     * @return the ids, trimmed and lower-cased; empty for a player not in memory
     */
    public @NotNull List<String> unlocked(@NotNull UUID player) {
        CrateRow row = row(player);
        return row == null ? List.of() : row.unlockedIds();
    }

    /**
     * How many rewards of the catalogue a player owns: unlocked, or owned
     * otherwise as {@link CrateCatalogue#ownsOtherwise} says. What the screens
     * show as {@code %unlocked_count%}.
     */
    public int ownedCount(@NotNull Player player) {
        Running<?> current = running;
        return current == null ? 0 : current.screens().ownedCount(player);
    }

    /** A player's keys, from memory; {@code 0} for a player not in memory. */
    public int keys(@NotNull UUID player) {
        CrateRow row = row(player);
        return row == null ? 0 : row.keys();
    }

    /** A player's keys whether or not they are here. */
    public @NotNull CompletableFuture<Integer> fetchKeys(@NotNull UUID player) {
        return require().store().fetch(player).thenApply(CrateRow::keys);
    }

    /**
     * Unlocks a reward for good, the way a crate does, for a player here or not.
     *
     * @param rewardId the reward's id; must not contain a comma
     * @return whether it was new to them
     */
    public @NotNull CompletableFuture<Boolean> unlock(@NotNull UUID player, @NotNull String rewardId) {
        String id = checked(rewardId);
        AtomicBoolean added = new AtomicBoolean();
        return require().store().edit(player, row -> {
            CrateRow after = row.withUnlocked(id);
            added.set(after != row);
            return after;
        }).thenApply(ignored -> added.get());
    }

    /**
     * Takes an unlocked reward away again, for a support ticket or a wiped season.
     *
     * @return whether they had it
     */
    public @NotNull CompletableFuture<Boolean> lock(@NotNull UUID player, @NotNull String rewardId) {
        String id = TierTable.normalise(rewardId);
        AtomicBoolean removed = new AtomicBoolean();
        return require().store().edit(player, row -> {
            CrateRow after = row.withoutUnlocked(id);
            removed.set(after != row);
            return after;
        }).thenApply(ignored -> removed.get());
    }

    /**
     * Takes back everything a player unlocked.
     *
     * @return how many there were
     */
    public @NotNull CompletableFuture<Integer> clearUnlocks(@NotNull UUID player) {
        AtomicInteger had = new AtomicInteger();
        return require().store().edit(player, row -> {
            had.set(row.unlockedIds().size());
            return row.withoutUnlocks();
        }).thenApply(ignored -> had.get());
    }

    /**
     * Adds keys to a player's account, here or not, or takes them away with a
     * negative number. Never goes below zero.
     *
     * @return the keys afterwards
     */
    public @NotNull CompletableFuture<Integer> addKeys(@NotNull UUID player, int keys) {
        return require().store().edit(player, row -> row.withKeys(row.keys() + keys)).thenApply(CrateRow::keys);
    }

    /**
     * Sets a player's keys, here or not. Never below zero.
     *
     * @return the keys afterwards
     */
    public @NotNull CompletableFuture<Integer> setKeys(@NotNull UUID player, int keys) {
        return require().store().edit(player, row -> row.withKeys(keys)).thenApply(CrateRow::keys);
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    /** Opens the question: how many crates to open. On the player's thread. */
    public void open(@NotNull Player player) {
        require().screens().open(player);
    }

    /** Opens this many crates with keys from the account, capped at {@code max-at-once}. On the player's thread. */
    public void spin(@NotNull Player player, int amount) {
        require().screens().spin(player, amount);
    }

    /**
     * One crate key that can be held, traded and dropped, drawn as
     * {@link CrateSettings#keyItem()} says. On the viewer's thread.
     *
     * @param viewer who it is drawn for, or {@code null}
     */
    public @NotNull ItemStack keyItem(@Nullable Player viewer) {
        CrateSettings.KeyItem look = require().settings().get().keyItem();
        Item definition = Item.of(look.material())
                .name(look.name())
                .lore(look.lore())
                .appearance(Appearance.builder().glow(look.glow()).hideAttributes(true).build())
                .build();
        ItemStack stack = Items.of(plugin).render(definition, viewer);
        values.set(stack, KEY_TAG, true);
        return stack;
    }

    /** Whether an item is one of this plugin's crate keys. Safe from any thread. */
    public boolean isKey(@Nullable ItemStack item) {
        return values.flag(item, KEY_TAG, false);
    }

    /**
     * Hands a player key items and tells them so. What does not fit waits in
     * the pending table. On the player's thread.
     *
     * @param amount how many; nothing happens below one
     */
    public void giveKeyItems(@NotNull Player player, int amount) {
        Running<?> current = require();
        if (amount < 1) return;
        ItemStack key = keyItem(player);
        int left = amount;
        while (left > 0) {
            ItemStack stack = key.clone();
            stack.setAmount(Math.min(left, key.getMaxStackSize()));
            left -= stack.getAmount();
            current.rewards().giveItem(player, stack);
        }
        say(player, current.messages().get().keysReceived(), amount);
    }

    // ------------------------------------------------------------------
    // Rarities
    // ------------------------------------------------------------------

    /** Every rarity id, lowest priority first. */
    public @NotNull List<String> tierIds() {
        return tiers().ids();
    }

    /**
     * The id a written rarity resolves to, as the crate resolves a reward's:
     * trimmed and lower-cased, and the first rarity for one that does not exist.
     */
    public @NotNull String tierId(@Nullable String tier) {
        return tiers().resolveId(tier);
    }

    /** A rarity, resolved as {@link #tierId} does. */
    public @NotNull CrateTier tier(@Nullable String tier) {
        return tiers().get(tier);
    }

    /** How many rewards of the catalogue sit in a rarity; {@code 0} for one that does not exist. */
    public int count(@NotNull String tierId) {
        List<?> pool = require().prizes().pools().get(TierTable.normalise(tierId));
        return pool == null ? 0 : pool.size();
    }

    /**
     * How likely an opening is to land on a rarity, as a share of one, among the
     * rarities that have anything in them — the odds a crate actually honours.
     *
     * @return {@code 0} for a rarity that does not exist or holds nothing
     */
    public double odds(@NotNull String tierId) {
        String id = TierTable.normalise(tierId);
        TierTable tiers = tiers();
        if (!tiers.ids().contains(id)) return 0;
        Map<String, ? extends List<?>> pools = require().prizes().pools();
        return tiers.shareOf(id, pools::containsKey);
    }

    // ------------------------------------------------------------------
    // The blocks that open it
    // ------------------------------------------------------------------

    /**
     * Binds a block so clicking it opens the crate, and writes the list.
     *
     * @return whether it was not bound already
     */
    public boolean bindBlock(@NotNull Block block) {
        return require().blocks().add(block.getLocation());
    }

    /**
     * Unbinds a block, and writes the list.
     *
     * @return whether it was bound
     */
    public boolean unbindBlock(@NotNull Block block) {
        return require().blocks().remove(block.getLocation());
    }

    /** Every bound block, as the settings write it. */
    public @NotNull List<String> boundBlocks() {
        Running<?> current = running;
        return current == null ? List.of() : current.blocks().all();
    }

    /**
     * Unbinds every block.
     *
     * @return how many were bound
     */
    public int clearBlocks() {
        return require().blocks().clear();
    }

    // ------------------------------------------------------------------

    private @Nullable CrateRow row(UUID player) {
        Running<?> current = running;
        return current == null ? null : current.store().row(player);
    }

    private TierTable tiers() {
        return new TierTable(require().settings().get().tiers());
    }

    private Running<?> require() {
        Running<?> current = running;
        if (current == null) throw new IllegalStateException("start() the crate of " + plugin.getName() + " first");
        return current;
    }

    private static String checked(String rewardId) {
        String id = TierTable.normalise(rewardId);
        if (id.isEmpty() || id.contains(",")) {
            throw new IllegalArgumentException("A crate reward id must be non-blank and have no comma: '" + rewardId + "'");
        }
        return id;
    }

    private void say(Player player, String line, int amount) {
        if (!line.isBlank()) Text.from(plugin, line).with("%amount%", amount).send(player);
    }

    /** A row changed: the question redraws, and so does whatever the plugin listens with. */
    private void changed(UUID uuid) {
        Running<?> current = running;
        if (current == null) return;
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null) return;
        Runnable tell = () -> {
            current.screens().refresh(player);
            for (Consumer<UUID> listener : listeners) {
                try {
                    listener.accept(uuid);
                } catch (RuntimeException failure) {
                    debug.error("A crate change listener failed", failure);
                }
            }
        };
        if (tasks.isOwnedBy(player)) tell.run(); else tasks.runAtEntity(player, tell);
    }

    /** For tests: the store a started crate reads and writes through. */
    @Nullable CrateStore storeForTests() {
        Running<?> current = running;
        return current == null ? null : current.store();
    }
}
