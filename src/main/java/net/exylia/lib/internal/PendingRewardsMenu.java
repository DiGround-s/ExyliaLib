package net.exylia.lib.internal;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.format.Dates;
import net.exylia.lib.task.TaskScheduler;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.text.Lines;
import net.exylia.lib.text.Lore;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSession;
import net.exylia.lib.util.reward.PendingBatch;
import net.exylia.lib.util.reward.PendingRewards;
import net.exylia.lib.util.reward.PluginRewards;
import net.exylia.lib.util.reward.RewardDelivery;
import net.exylia.lib.util.reward.RewardEntry;
import net.exylia.lib.util.reward.Rewards;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The screens behind {@code /exylialib pendingrewards}: who is owed rewards, and
 * each batch they are owed, across every plugin that keeps a store.
 *
 * <p>Every read and every take runs off the main thread, since each store is a
 * database; the rows come back onto the viewer's. A screen reopened while a read
 * was in flight is left alone. Cancelling asks twice: the first click only arms
 * the row, and the armed batch lives in the session, never in a shared map.
 *
 * <p>Compiled into {@link UpdatesMenu}'s {@link PluginMenus}: a plugin's menus
 * are unloaded all at once, so two owners of the same namespace would wipe each
 * other on a reload.
 */
public final class PendingRewardsMenu {

    /** Who may open the screens. */
    static final String PERMISSION = "exylialib.pendingrewards";

    static final String PLAYERS = "pending";
    static final String BATCHES = "pending_player";

    private static final String PLAYER_KEY = "pending_uuid";
    private static final String NAME_KEY = "pending_name";
    private static final String ARMED_KEY = "pending_armed";

    /** Rewards listed on a batch before the rest is summarised. */
    private static final int MAX_REWARDS = 8;

    /** Characters a reward line may take before it is cut. */
    private static final int MAX_WIDTH = 40;

    private static volatile Plugin library;
    private static volatile PluginMenus menus;

    /** A player on the first screen. */
    record Owed(UUID player, String name) {
    }

    /** A batch on the second screen; {@code batch} is {@code null} for a store that cannot be listed. */
    record Batch(String plugin, UUID player, @Nullable String batch) {
    }

    private PendingRewardsMenu() {
    }

    /**
     * Registers the actions. Once, before the screens are loaded.
     *
     * @param plugin the library
     */
    static void init(@NotNull Plugin plugin) {
        library = plugin;
        PluginActions actions = Actions.of(plugin, UpdatesMenu.NAMESPACE);
        actions.registerSync("pending_fill", (context, arguments) -> {
            fill(context.require(UiKeys.SESSION));
            return ActionResult.success();
        });
        actions.registerSync("pending_open", (context, arguments) -> openPlayer(context));
        actions.registerSync("pending_give", (context, arguments) -> give(context));
        actions.registerSync("pending_give_all", (context, arguments) -> giveAll(context));
        actions.registerSync("pending_cancel", (context, arguments) -> cancel(context));
        actions.registerSync("pending_keep", (context, arguments) -> keep(context));
    }

    /** Compiles the screens into the library's menus. */
    static void load(@NotNull PluginMenus target) {
        YamlConfiguration players = yaml(PLAYERS_YAML);
        frame(players);
        playersWords(players);
        target.load(PLAYERS, players);
        YamlConfiguration batches = yaml(BATCHES_YAML);
        frame(batches);
        batchesWords(batches);
        target.load(BATCHES, batches);
        menus = target;
    }

    /** Forgets the screens, when the library disables. */
    static void release() {
        menus = null;
        library = null;
    }

    /**
     * Opens the list of everybody who is owed something.
     *
     * @param viewer who reviews; any thread
     */
    public static void open(@NotNull Player viewer) {
        PluginMenus current = menus;
        if (current != null) {
            current.open(viewer, PLAYERS);
        }
    }

    /**
     * Opens what one player is owed.
     *
     * @param viewer who reviews; any thread
     * @param player whose rewards
     * @param name   what to call them
     */
    public static void open(@NotNull Player viewer, @NotNull UUID player, @NotNull String name) {
        PluginMenus current = menus;
        if (current != null) {
            current.open(viewer, BATCHES, Map.of(PLAYER_KEY, player.toString(), NAME_KEY, name));
        }
    }

    /**
     * Tells the console who is owed what, since it cannot open a menu.
     *
     * @param sender who asked; any thread
     * @param header the panel's first line
     */
    static void panel(@NotNull org.bukkit.command.CommandSender sender, @NotNull String header) {
        Plugin plugin = library;
        if (plugin == null) return;
        TaskScheduler tasks = Tasks.of(plugin);
        tasks.runAsync(() -> {
            Map<UUID, Map<String, Integer>> owed = owedByPlayer();
            StringBuilder raw = new StringBuilder(header).append(" ").append(Phrases.tr("{muted}pending rewards"));
            if (owed.isEmpty()) {
                raw.append("\n").append(Phrases.tr("{letters_black}▎ {success}No reward is waiting to be delivered."));
            }
            owed.forEach((player, byPlugin) -> {
                List<String> parts = new ArrayList<>(byPlugin.size());
                byPlugin.forEach((name, batches) -> parts.add("{letters}" + name + " {info}" + batches));
                raw.append("\n").append("{letters_black}▎ {highlight}").append(nameOf(player))
                        .append(" {letters_black}» ").append(String.join("{letters_black}, ", parts));
            });
            tasks.run(() -> Text.of(raw.toString()).send(sender));
        });
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException broken) {
            throw new IllegalStateException("The library's own pending rewards menu does not parse", broken);
        }
        return yaml;
    }

    // ------------------------------------------------------------------ reads

    /**
     * Who is owed what, by player and then by plugin.
     *
     * <p>Off the main thread. A store that cannot be listed, or fails to, is
     * left out rather than taking the whole screen with it.
     */
    static Map<UUID, Map<String, Integer>> owedByPlayer() {
        Map<UUID, Map<String, Integer>> owed = new LinkedHashMap<>();
        for (PluginRewards rewards : sorted()) {
            PendingRewards store = rewards.pending();
            if (store == null || !store.browsable()) continue;
            try {
                store.owed().forEach((player, batches) -> owed
                        .computeIfAbsent(player, key -> new TreeMap<>())
                        .put(rewards.plugin().getName(), batches));
            } catch (RuntimeException unreadable) {
                rewards.plugin().getLogger().warning("Could not list pending rewards: " + unreadable);
            }
        }
        return owed;
    }

    /** One player's batches, plugin by plugin, oldest first. Off the main thread. */
    private static List<UiEntry> batchRows(UUID player, @Nullable String armed) {
        boolean online = Bukkit.getPlayer(player) != null;
        List<UiEntry> rows = new ArrayList<>();
        for (PluginRewards rewards : sorted()) {
            PendingRewards store = rewards.pending();
            if (store == null) continue;
            String plugin = rewards.plugin().getName();
            if (!store.browsable()) {
                rows.add(sealedRow(plugin, player));
                continue;
            }
            List<PendingBatch> batches;
            try {
                batches = store.peek(player);
            } catch (RuntimeException unreadable) {
                rewards.plugin().getLogger().warning("Could not list the rewards owed to " + player
                        + ": " + unreadable);
                continue;
            }
            for (PendingBatch batch : batches) {
                rows.add(batchRow(plugin, player, batch, online, batch.id().equals(armed)));
            }
        }
        return rows;
    }

    private static List<PluginRewards> sorted() {
        return Rewards.all().stream()
                .sorted(Comparator.comparing(rewards -> rewards.plugin().getName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static @Nullable PluginRewards rewardsOf(String plugin) {
        return Rewards.all().stream().filter(rewards -> rewards.plugin().getName().equals(plugin))
                .findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ rows

    static List<UiEntry> playerRows(Map<UUID, Map<String, Integer>> owed) {
        List<UiEntry> rows = new ArrayList<>(owed.size());
        owed.forEach((player, byPlugin) -> {
            String name = nameOf(player);
            int total = byPlugin.values().stream().mapToInt(Integer::intValue).sum();
            List<String> lines = new ArrayList<>(byPlugin.size());
            byPlugin.forEach((plugin, batches) -> lines.add(
                    "  {letters_black}· {letters}" + plugin + "{letters_black}: {info}" + batches));
            rows.add(UiEntry.of(new Owed(player, name))
                    .with("player", name)
                    .with("batches", total)
                    .withFormatted("plugins", String.join(Lines.NEWLINE, lines))
                    .withFormatted("status", status(player))
                    .build());
        });
        rows.sort(Comparator.comparing(row -> row.value(Owed.class).map(Owed::name).orElse(""),
                String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    static UiEntry batchRow(String plugin, UUID player, PendingBatch batch, boolean online, boolean armed) {
        List<RewardEntry> rewards = batch.rewards();
        String icon = rewards.isEmpty() ? "PAPER" : rewards.get(0).resolvedIcon();
        return UiEntry.of(new Batch(plugin, player, batch.id()))
                .with("icon", icon)
                .with("plugin", plugin)
                .with("count", rewards.size())
                .withFormatted("rewards", rewardLines(rewards))
                .with("source", batch.source() == null || batch.source().isBlank()
                        ? Phrases.tr("unknown") : batch.source())
                .with("owed", batch.owedAt() <= 0 ? Phrases.tr("unknown") : Dates.relativeMillis(batch.owedAt()))
                .template(armed ? "armed" : online ? null : "offline")
                .build();
    }

    private static UiEntry sealedRow(String plugin, UUID player) {
        return UiEntry.of(new Batch(plugin, player, null))
                .with("plugin", plugin)
                .template("sealed")
                .build();
    }

    /**
     * The rewards as lore lines, each named as its menu would name it.
     *
     * <p>Formatted, because a reward's name is the server owner's own text and
     * carries its own colours.
     */
    static String rewardLines(List<RewardEntry> rewards) {
        List<String> lines = new ArrayList<>();
        for (RewardEntry reward : rewards) {
            if (lines.size() == MAX_REWARDS - 1 && rewards.size() > MAX_REWARDS) {
                lines.add(" {muted}" + Phrases.tr("… {0} more", rewards.size() - lines.size()));
                break;
            }
            String name = reward.displayName();
            if (name.length() > MAX_WIDTH) {
                name = name.substring(0, MAX_WIDTH - 1) + "…";
            }
            lines.add(" {letters}" + name);
        }
        if (lines.isEmpty()) {
            lines.add(" {muted}" + Phrases.tr("nothing"));
        }
        return String.join(Lines.NEWLINE, lines);
    }

    private static String status(UUID player) {
        return Bukkit.getPlayer(player) != null ? Phrases.tr("{success}online") : Phrases.tr("{muted}offline");
    }

    private static String nameOf(UUID player) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) return online.getName();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(player);
        return offline.getName() != null ? offline.getName() : player.toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ actions

    private static void fill(UiSession session) {
        Plugin plugin = library;
        if (plugin == null) return;
        Player viewer = session.viewer();
        int generation = session.generation();
        UUID player = session.context(PLAYER_KEY, String.class).map(UUID::fromString).orElse(null);
        String armed = session.context(ARMED_KEY, String.class).orElse(null);
        TaskScheduler tasks = Tasks.of(plugin);
        tasks.runAsync(() -> {
            List<UiEntry> rows = player == null ? playerRows(owedByPlayer()) : batchRows(player, armed);
            tasks.runAtEntity(viewer, () -> {
                if (session.isOpen() && session.generation() == generation) {
                    session.entries(rows);
                }
            });
        });
    }

    private static ActionResult openPlayer(ActionContext context) {
        Owed row = context.get(UiKeys.ENTRY).filter(Owed.class::isInstance).map(Owed.class::cast).orElse(null);
        if (row == null) return ActionResult.stop("no row");
        open(context.player(), row.player(), row.name());
        return ActionResult.success();
    }

    private static ActionResult give(ActionContext context) {
        UiSession session = context.require(UiKeys.SESSION);
        Batch row = context.get(UiKeys.ENTRY).filter(Batch.class::isInstance).map(Batch.class::cast).orElse(null);
        if (row == null || row.batch() == null) return ActionResult.stop("no row");
        disarm(session);
        Player target = Bukkit.getPlayer(row.player());
        PluginRewards rewards = rewardsOf(row.plugin());
        if (target == null || rewards == null) {
            Text.of(Phrases.tr("{error}✘ {letters}{0} is offline. Their rewards wait for their next join.",
                    nameOf(row.player()))).send(context.player());
            fill(session);
            return ActionResult.success();
        }
        Player viewer = context.player();
        rewards.claim(target, row.batch(), delivery -> {
            audit(viewer, "gave", row, delivery.given());
            afterDelivery(viewer, session, target.getName(), delivery);
        });
        return ActionResult.success();
    }

    private static ActionResult giveAll(ActionContext context) {
        UiSession session = context.require(UiKeys.SESSION);
        UUID player = session.context(PLAYER_KEY, String.class).map(UUID::fromString).orElse(null);
        if (player == null) return ActionResult.stop("no player");
        disarm(session);
        Player target = Bukkit.getPlayer(player);
        Player viewer = context.player();
        if (target == null) {
            Text.of(Phrases.tr("{error}✘ {letters}{0} is offline. Their rewards wait for their next join.",
                    nameOf(player))).send(viewer);
            return ActionResult.success();
        }
        for (PluginRewards rewards : sorted()) {
            PendingRewards store = rewards.pending();
            if (store == null) continue;
            rewards.claim(target, delivery -> {
                Batch everything = new Batch(rewards.plugin().getName(), player, "*");
                audit(viewer, "gave", everything, delivery.given());
                afterDelivery(viewer, session, target.getName(), delivery);
            });
        }
        return ActionResult.success();
    }

    private static ActionResult cancel(ActionContext context) {
        UiSession session = context.require(UiKeys.SESSION);
        Batch row = context.get(UiKeys.ENTRY).filter(Batch.class::isInstance).map(Batch.class::cast).orElse(null);
        if (row == null || row.batch() == null) return ActionResult.stop("no row");
        if (!row.batch().equals(session.context(ARMED_KEY, String.class).orElse(null))) {
            // The first click only arms the row; the second one deletes.
            session.context(ARMED_KEY, row.batch());
            fill(session);
            return ActionResult.success();
        }
        disarm(session);
        PluginRewards rewards = rewardsOf(row.plugin());
        PendingRewards store = rewards == null ? null : rewards.pending();
        Plugin plugin = library;
        if (store == null || plugin == null) return ActionResult.stop("no store");
        Player viewer = context.player();
        TaskScheduler tasks = Tasks.of(plugin);
        tasks.runAsync(() -> {
            List<RewardEntry> taken;
            try {
                taken = store.take(row.player(), row.batch());
            } catch (RuntimeException unwritable) {
                rewards.plugin().getLogger().warning("Could not cancel pending rewards: " + unwritable);
                taken = List.of();
            }
            int count = taken.size();
            if (count > 0) {
                audit(viewer, "cancelled", row, count);
            }
            tasks.runAtEntity(viewer, () -> {
                Text.of(count > 0
                        ? Phrases.tr("{success}✔ {letters}Cancelled {info}{0} {letters}reward(s) owed to {highlight}{1}",
                                count, nameOf(row.player()))
                        : Phrases.tr("{warning}✘ {letters}That batch was already gone.")).send(viewer);
                fill(session);
            });
        });
        return ActionResult.success();
    }

    private static void afterDelivery(Player viewer, UiSession session, String target, RewardDelivery delivery) {
        Plugin plugin = library;
        if (plugin == null) return;
        // Told on the target's thread; the viewer's screen is redrawn on theirs.
        Tasks.of(plugin).runAtEntity(viewer, () -> {
            feedback(delivery, target).send(viewer);
            fill(session);
        });
    }

    static Text feedback(RewardDelivery delivery, String target) {
        if (delivery.results().isEmpty()) {
            return Text.of(Phrases.tr("{warning}✘ {letters}That batch was already gone."));
        }
        return Text.of(Phrases.tr("{success}✔ {letters}Gave {info}{0} {letters}reward(s) to {highlight}{1}",
                delivery.given(), target));
    }

    private static void disarm(UiSession session) {
        // Blank rather than removed: a session's context takes no nulls.
        session.context(ARMED_KEY, "");
    }

    private static ActionResult keep(ActionContext context) {
        UiSession session = context.require(UiKeys.SESSION);
        disarm(session);
        fill(session);
        return ActionResult.success();
    }

    /** One console line per staff action, so a missing reward can be traced. */
    private static void audit(Player viewer, String verb, Batch row, int count) {
        Plugin plugin = library;
        if (plugin == null) return;
        plugin.getLogger().info("[pending rewards] " + viewer.getName() + " " + verb + " " + count
                + " reward(s) of " + row.plugin() + " batch " + row.batch() + " owed to " + row.player());
    }

    // ------------------------------------------------------------------ screens

    private static final String FRAME = """
            filler:
              global:
                material: BLACK_STAINED_GLASS_PANE
                hide_tooltip: true
              pagination:
                material: LIME_STAINED_GLASS_PANE
            """;

    private static final String NAVIGATION = """
              navigation:
                previous:
                  slot: 45
                  material: ARROW
                next:
                  slot: 53
                  material: ARROW
            """;

    static final String PLAYERS_YAML = """
            size: 54
            open-actions:
              - 'exylialib:pending_fill'
            """ + FRAME + """
            pagination:
              slots: '10-16,19-25,28-34,37-43'
              item_template:
                material: 'playerhead-%player%'
                name: '{letters}Player {letters_black}▸ <gradient:{highlight}:{warning_light}>%player%</gradient> &8[{warning}%batches%&8]'
                actions:
                  - 'exylialib:pending_open'
            """ + NAVIGATION + """
            items:
              info:
                slot: 4
                material: NETHER_STAR
              close:
                slot: 49
                material: BARRIER
                actions:
                  - 'close'
              refresh:
                slot: 50
                material: CLOCK
                actions:
                  - 'exylialib:pending_fill'
            """;

    static final String BATCHES_YAML = """
            size: 54
            parent: 'exylialib:pending'
            open-actions:
              - 'exylialib:pending_fill'
            """ + FRAME + """
            pagination:
              slots: '10-16,19-25,28-34,37-43'
              item_template:
                material: '%icon%'
                name: '{letters}Batch {letters_black}▸ <gradient:{highlight}:{warning_light}>%plugin%</gradient> &8[{info}%count%&8]'
                actions:
                  - 'left: exylialib:pending_give'
                  - 'shift_right: exylialib:pending_cancel'
              offline_template:
                material: '%icon%'
                name: '{letters}Batch {letters_black}▸ <gradient:{highlight}:{warning_light}>%plugin%</gradient> &8[{info}%count%&8]'
                actions:
                  - 'shift_right: exylialib:pending_cancel'
              armed_template:
                material: BARRIER
                name: '{letters}Batch {letters_black}▸ <gradient:{error}:{error_light}>%plugin%</gradient> &8[{error}%count%&8]'
                actions:
                  - 'shift_right: exylialib:pending_cancel'
                  - 'left,right,shift_left: exylialib:pending_keep'
              sealed_template:
                material: GRAY_DYE
                name: '{letters}Batch {letters_black}▸ <gradient:{highlight}:{warning_light}>%plugin%</gradient>'
            """ + NAVIGATION + """
            items:
              info:
                slot: 4
                material: 'playerhead-%pending_name%'
              give:
                slot: 48
                material: LIME_DYE
                actions:
                  - 'exylialib:pending_give_all'
              back:
                slot: 49
                material: BARRIER
                actions:
                  - 'back'
              refresh:
                slot: 50
                material: CLOCK
                actions:
                  - 'exylialib:pending_fill'
            """;

    // ------------------------------------------------------------------ words

    // Set on the parsed screens rather than written into the YAML, as in
    // UpdatesMenu: a reload in another language redraws them, and a quote in a
    // translation can never break the YAML.

    private static final Lore.Tone TONE = Lore.Tone.HIGHLIGHT;

    private static void frame(YamlConfiguration yaml) {
        yaml.set("filler.pagination.name", Phrases.tr("{success}All delivered"));
        yaml.set("filler.pagination.lore", List.of(Phrases.tr("{letters_black}No reward is waiting to be delivered.")));
        yaml.set("pagination.navigation.previous.name", Lore.name(Lore.Tone.ERROR, Phrases.tr("Page"), "← " + Phrases.tr("Previous")));
        yaml.set("pagination.navigation.next.name", Lore.name(Lore.Tone.SUCCESS, Phrases.tr("Page"), Phrases.tr("Next") + " →"));
        String page = Phrases.tr("  {letters_black}· {letters}Page{letters_black}: {info}%current_page%{letters_black}/{info}%total_pages%");
        yaml.set("pagination.navigation.previous.lore", List.of(page));
        yaml.set("pagination.navigation.next.lore", List.of(page));
    }

    private static void playersWords(YamlConfiguration yaml) {
        yaml.set("title", Phrases.tr("{primary}&lPENDING REWARDS {muted}%current_page%/%total_pages%"));
        yaml.set("pagination.item_template.lore", Lore.of(TONE).subtitle(Phrases.tr("Owed rewards"))
                .section("♦", Phrases.tr("Owed by")).line("%plugins%")
                .section("✦", Phrases.tr("Status"))
                .line(Phrases.tr("  {letters_black}· {info}{icon:ping} {letters}Player{letters_black}: %status%"))
                .action(Phrases.tr("Click to review"))
                .lines());
        yaml.set("items.info.name", Lore.name(TONE, Phrases.tr("Pending rewards")));
        yaml.set("items.info.lore", Lore.of(TONE).subtitle(Phrases.tr("Reward queue"))
                .section(Phrases.tr("Information"))
                .line(Phrases.tr(" {letters}Rewards a player could not receive"))
                .line(Phrases.tr(" {letters}wait here until their {highlight}next join{letters}."))
                .lines());
        yaml.set("items.close.name", Lore.name(Lore.Tone.ERROR, Phrases.tr("Rewards"), Phrases.tr("Close")));
        yaml.set("items.refresh.name", Lore.name(Lore.Tone.INFO, Phrases.tr("Rewards"), Phrases.tr("Refresh")));
    }

    private static Lore details(String subtitle) {
        return Lore.of(TONE).subtitle(subtitle)
                .section("♦", Phrases.tr("Rewards")).line("%rewards%")
                .section("✦", Phrases.tr("Status"))
                .line(Phrases.tr("  {letters_black}· {info}{icon:book} {letters}Source{letters_black}: {info}%source%"))
                .line(Phrases.tr("  {letters_black}· {info}{icon:clock} {letters}Owed{letters_black}: {info}%owed%"));
    }

    private static void batchesWords(YamlConfiguration yaml) {
        yaml.set("title", Phrases.tr("{primary}&l%pending_name% {muted}%current_page%/%total_pages%"));
        yaml.set("pagination.item_template.lore", details(Phrases.tr("Owed batch"))
                .action(Phrases.tr("Left click to give it now"))
                .line("{error}{icon:right_click} " + Phrases.tr("Shift + right click to cancel it"))
                .lines());
        yaml.set("pagination.offline_template.lore", details(Phrases.tr("Owed batch"))
                .line("    " + Phrases.tr("{muted}Offline: delivered on their next join."))
                .line("")
                .line("{error}{icon:right_click} " + Phrases.tr("Shift + right click to cancel it"))
                .lines());
        yaml.set("pagination.armed_template.lore", Lore.of(Lore.Tone.ERROR).subtitle(Phrases.tr("Danger zone"))
                .section("♦", Phrases.tr("Rewards")).line("%rewards%")
                .section(Phrases.tr("Information"))
                .line(Phrases.tr(" {error}Cancelling deletes this batch."))
                .line(Phrases.tr(" {letters}The player never receives it."))
                .action(Phrases.tr("Shift + right click again to confirm"))
                .line(Lore.Tone.ERROR.colour() + "{icon:click} " + Phrases.tr("Any other click keeps it"))
                .lines());
        yaml.set("pagination.sealed_template.lore", Lore.of(TONE).subtitle(Phrases.tr("Owed batch"))
                .section(Phrases.tr("Information"))
                .line(Phrases.tr(" {letters}This plugin keeps its own table and"))
                .line(Phrases.tr(" {letters}cannot list it yet. Update it to"))
                .line(Phrases.tr(" {letters}review its rewards here."))
                .lines());
        yaml.set("items.info.name", Lore.name(TONE, "%pending_name%"));
        yaml.set("items.info.lore", Lore.of(TONE).subtitle(Phrases.tr("Owed rewards"))
                .section(Phrases.tr("Information"))
                .line(Phrases.tr(" {letters}Every batch this player is owed,"))
                .line(Phrases.tr(" {letters}oldest first, plugin by plugin."))
                .lines());
        yaml.set("items.give.name", Lore.name(Lore.Tone.SUCCESS, Phrases.tr("Rewards"), Phrases.tr("Give everything")));
        yaml.set("items.give.lore", Lore.of(Lore.Tone.SUCCESS).subtitle(Phrases.tr("Rewards action"))
                .section(Phrases.tr("Information"))
                .line(Phrases.tr(" {letters}Hands over every batch now."))
                .line(Phrases.tr(" {letters}Only while the player is {highlight}online{letters}."))
                .action(Phrases.tr("Click to give all"))
                .lines());
        yaml.set("items.back.name", Lore.name(Lore.Tone.ERROR, Phrases.tr("Back"), Phrases.tr("Players")));
        yaml.set("items.back.lore", List.of("{error}{icon:click} " + Phrases.tr("Click to go back")));
        yaml.set("items.refresh.name", Lore.name(Lore.Tone.INFO, Phrases.tr("Rewards"), Phrases.tr("Refresh")));
    }
}
