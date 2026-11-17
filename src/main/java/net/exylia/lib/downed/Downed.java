package net.exylia.lib.downed;

import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.PluginMessages;
import net.exylia.lib.text.Text;
import net.exylia.lib.text.Values;
import net.exylia.lib.util.TimeFormats;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Downed instead of dead: a lethal hit leaves the player crawling with a bleed-out timer, and a
 * teammate who sneaks beside them long enough brings them back up. While down they cannot attack,
 * use or eat, and nothing hurts them.
 *
 * <pre>{@code
 * downed = Downed.of(this)
 *         .when(player -> runs.isInRun(player))                      // only inside a dungeon
 *         .reviver((helper, down) -> Parties.together(helper.getUniqueId(), down.getUniqueId()))
 *         .bleedOut(Duration.ofSeconds(30)).revive(Duration.ofSeconds(4))
 *         .bledOut(player -> runs.eliminate(player))                // instead of a real death
 *         .onEnd((player, end) -> runs.checkWipe())
 *         .messages(() -> messages.get().downed())
 *         .start();
 * }</pre>
 *
 * <p>The crawl is a client-side barrier above the head plus the swimming pose; the block is put back
 * when they stand. A held totem still saves the player the vanilla way. Every per-player step runs
 * on that player's own thread, so it is Folia safe.
 *
 * @since 1.621.0
 */
public final class Downed implements Listener {

    private final Plugin plugin;
    private final TaskScheduler tasks;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> reviving = new ConcurrentHashMap<>();

    private Predicate<Player> when = player -> true;
    private BiPredicate<Player, Player> reviver = (helper, down) -> true;
    private Duration bleedOut = Duration.ofSeconds(30);
    private Duration revive = Duration.ofSeconds(4);
    private double range = 2.0;
    private double health = 6.0;
    private @Nullable Consumer<Player> bledOut;
    private BiConsumer<Player, DownedEnd> onEnd = (player, end) -> { };
    private Supplier<DownedMessages> messages = DownedMessages::new;
    private volatile boolean started;

    private Downed(Plugin plugin) {
        this.plugin = plugin;
        this.tasks = Tasks.of(plugin);
    }

    /** Starts configuring downs for a plugin. Nothing happens until {@link #start}. */
    public static @NotNull Downed of(@NotNull Plugin plugin) {
        return new Downed(plugin);
    }

    /** Who goes down instead of dying; everyone else dies as usual. Default: everyone. */
    public @NotNull Downed when(@NotNull Predicate<Player> when) {
        this.when = when;
        return this;
    }

    /** Who may revive whom, helper first. Default: anyone not downed. */
    public @NotNull Downed reviver(@NotNull BiPredicate<Player, Player> reviver) {
        this.reviver = reviver;
        return this;
    }

    /** How long a downed player waits before bleeding out. Default 30s. */
    public @NotNull Downed bleedOut(@NotNull Duration bleedOut) {
        this.bleedOut = bleedOut;
        return this;
    }

    /** How long a helper must sneak beside them. Default 4s. */
    public @NotNull Downed revive(@NotNull Duration revive) {
        this.revive = revive;
        return this;
    }

    /** How close the helper must be, in blocks. Default 2. */
    public @NotNull Downed range(double range) {
        this.range = range;
        return this;
    }

    /** The health a revived player stands up with. Default 6 (three hearts). */
    public @NotNull Downed health(double health) {
        this.health = health;
        return this;
    }

    /** What happens on bleed-out instead of a real death (runs on the player's thread). Default: they die. */
    public @NotNull Downed bledOut(@NotNull Consumer<Player> bledOut) {
        this.bledOut = bledOut;
        return this;
    }

    /** Hears every end, on the player's thread (QUIT: on the quit event's). */
    public @NotNull Downed onEnd(@NotNull BiConsumer<Player, DownedEnd> onEnd) {
        this.onEnd = onEnd;
        return this;
    }

    /** The plugin's lines, read every time they show, so a reload restyles them. */
    public @NotNull Downed messages(@NotNull Supplier<DownedMessages> messages) {
        this.messages = messages;
        return this;
    }

    /** Begins listening. */
    public @NotNull Downed start() {
        if (!started) {
            started = true;
            Bukkit.getPluginManager().registerEvents(this, plugin);
        }
        return this;
    }

    /** Stands every downed player back up ({@link DownedEnd#CANCELLED}) and stops listening. */
    public void stop() {
        started = false;
        HandlerList.unregisterAll(this);
        for (UUID id : states.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) onPlayer(player, () -> end(player, DownedEnd.CANCELLED));
        }
    }

    /** Whether the player is down right now. */
    public boolean isDowned(@NotNull Player player) {
        return states.containsKey(player.getUniqueId());
    }

    /** How many are down, for a wipe check: everyone in a group down means the group fell. */
    public long downed(@NotNull Iterable<UUID> players) {
        long count = 0;
        for (UUID id : players) if (states.containsKey(id)) count++;
        return count;
    }

    /** Puts the player down now, as a lethal hit would. Call on the player's thread. */
    public void down(@NotNull Player player) {
        if (states.containsKey(player.getUniqueId()) || player.isDead()) return;
        State state = new State(System.currentTimeMillis() + bleedOut.toMillis());
        states.put(player.getUniqueId(), state);
        player.setHealth(Math.min(1.0, player.getHealth() > 0 ? player.getHealth() : 1.0));
        player.setSprinting(false);
        player.setPose(Pose.SWIMMING, true);
        player.showBossBar(state.bar);
        state.task = tasks.runAtEntityTimer(player, 1L, 1L, () -> tick(player, state));
    }

    /** Stands the player up as if revived, without a helper. Call on the player's thread. */
    public void lift(@NotNull Player player) {
        if (states.containsKey(player.getUniqueId())) end(player, DownedEnd.CANCELLED);
    }

    // ── The tick ──────────────────────────────────────────────────────────

    private void tick(Player player, State state) {
        if (!player.isOnline() || states.get(player.getUniqueId()) != state) {
            state.task.cancel();
            return;
        }
        long now = System.currentTimeMillis();
        holdCrawl(player, state);
        Player helper = helper(player, state);
        if (helper == null) {
            if (state.helper != null) release(state);
            state.progress = 0;
        } else if (!helper.getUniqueId().equals(state.helper)) {
            release(state);
            state.helper = helper.getUniqueId();
            state.progress = 0;
            reviving.put(helper.getUniqueId(), player.getUniqueId());
            helper.showBossBar(state.bar);
        } else {
            state.progress++;
            state.deadline += 50;  // the clock waits while someone helps
        }
        long reviveTicks = Math.max(1, revive.toMillis() / 50);
        if (state.helper != null && state.progress >= reviveTicks) {
            Player by = Bukkit.getPlayer(state.helper);
            end(player, DownedEnd.REVIVED);
            player.setHealth(Math.min(health, maxHealth(player)));
            Values values = Values.of().putText("player", by == null ? "?" : by.getName());
            PluginMessages.of(plugin).send(player, messages.get().revived(), values);
            if (by != null) {
                Values back = Values.of().putText("player", player.getName());
                onPlayer(by, () -> PluginMessages.of(plugin).send(by, messages.get().reviver(), back));
            }
            return;
        }
        if (now >= state.deadline) {
            end(player, DownedEnd.BLED_OUT);
            if (bledOut != null) bledOut.accept(player);
            else player.setHealth(0);
            return;
        }
        if (now / 250 != state.drawn) {
            state.drawn = now / 250;
            draw(player, state, now, reviveTicks);
        }
    }

    private @Nullable Player helper(Player player, State state) {
        if (state.helper != null) {
            Player current = Bukkit.getPlayer(state.helper);
            if (current != null && canHelp(current, player)) return current;
        }
        for (Player near : player.getLocation().getNearbyPlayers(range)) {
            if (near != player && canHelp(near, player)) return near;
        }
        return null;
    }

    private boolean canHelp(Player helper, Player down) {
        return helper.isSneaking() && !helper.isDead() && !states.containsKey(helper.getUniqueId())
                && helper.getWorld().equals(down.getWorld())
                && helper.getLocation().distanceSquared(down.getLocation()) <= range * range
                && (!reviving.containsKey(helper.getUniqueId()) || down.getUniqueId().equals(reviving.get(helper.getUniqueId())))
                && reviver.test(helper, down);
    }

    private void draw(Player player, State state, long now, long reviveTicks) {
        DownedMessages lines = messages.get();
        if (state.helper != null) {
            Player helper = Bukkit.getPlayer(state.helper);
            state.bar.name(Text.component(lines.reviving().replace("%player%", helper == null ? "?" : helper.getName())));
            state.bar.color(BossBar.Color.GREEN);
            state.bar.progress(clamp((float) state.progress / reviveTicks));
        } else {
            long left = Math.max(0, state.deadline - now);
            state.bar.name(Text.component(lines.downed().replace("%time%",
                    TimeFormats.render(Duration.ofMillis(left), TimeFormats.Style.AUTO))));
            state.bar.color(BossBar.Color.RED);
            state.bar.progress(clamp((float) left / Math.max(1, bleedOut.toMillis())));
        }
    }

    /** Keeps a barrier the client sees just above the head, so it stays crawling as it moves. */
    private void holdCrawl(Player player, State state) {
        Block above = player.getLocation().getBlock().getRelative(0, 1, 0);
        Location at = above.getLocation();
        if (at.equals(state.barrier)) return;
        restoreBarrier(player, state);
        if (above.getType().isAir()) {
            player.sendBlockChange(at, Material.BARRIER.createBlockData());
            state.barrier = at;
        }
        player.setPose(Pose.SWIMMING, true);
    }

    private void restoreBarrier(Player player, State state) {
        if (state.barrier == null) return;
        if (state.barrier.getWorld() == player.getWorld()) {
            player.sendBlockChange(state.barrier, state.barrier.getBlock().getBlockData());
        }
        state.barrier = null;
    }

    private void release(State state) {
        if (state.helper == null) return;
        reviving.remove(state.helper);
        Player helper = Bukkit.getPlayer(state.helper);
        if (helper != null) helper.hideBossBar(state.bar);
        state.helper = null;
    }

    private void end(Player player, DownedEnd end) {
        State state = states.remove(player.getUniqueId());
        if (state == null) return;
        if (state.task != null) state.task.cancel();
        release(state);
        player.hideBossBar(state.bar);
        if (player.isOnline()) {
            restoreBarrier(player, state);
            player.setPose(Pose.STANDING, false);
        }
        try {
            onEnd.accept(player, end);
        } catch (RuntimeException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "A downed end hook failed", e);
        }
    }

    private void onPlayer(Player player, Runnable work) {
        if (tasks.isOwnedBy(player)) work.run();
        else tasks.runAtEntity(player, work);
    }

    private static double maxHealth(Player player) {
        var attribute = player.getAttribute(net.exylia.lib.platform.Attributes.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    // ── Events ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID downedBy = reviving.get(player.getUniqueId());
        if (downedBy != null) {
            State state = states.get(downedBy);
            if (state != null) state.progress = 0;  // a hit breaks the helper's focus
        }
        if (states.containsKey(player.getUniqueId())) {
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) return;
            event.setCancelled(true);
            return;
        }
        if (player.getHealth() - event.getFinalDamage() > 0) return;
        if (holdsTotem(player) || !when.test(player)) return;
        event.setCancelled(true);
        down(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && states.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        if (states.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEat(PlayerItemConsumeEvent event) {
        if (states.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        end(event.getPlayer(), DownedEnd.CANCELLED);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID helping = reviving.remove(player.getUniqueId());
        if (helping != null) {
            State state = states.get(helping);
            if (state != null && player.getUniqueId().equals(state.helper)) state.helper = null;
        }
        end(player, DownedEnd.QUIT);
    }

    private static boolean holdsTotem(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || player.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    private static final class State {
        final BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1f,
                BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        volatile long deadline;
        volatile int progress;
        volatile @Nullable UUID helper;
        @Nullable Location barrier;
        long drawn = -1;
        TaskHandle task;

        State(long deadline) {
            this.deadline = deadline;
        }
    }
}
