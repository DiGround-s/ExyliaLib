package net.exylia.lib.util.crate.internal;

import net.exylia.lib.action.ActionArguments;
import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.Actions;
import net.exylia.lib.config.Languages;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.input.PluginInputs;
import net.exylia.lib.settings.internal.SettingsMenu;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import net.exylia.lib.text.Values;
import net.exylia.lib.ui.PluginMenus;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSession;
import net.exylia.lib.util.crate.CrateCatalogue;
import net.exylia.lib.util.crate.PluginCrates;
import net.exylia.lib.util.reward.PluginRewards;
import net.exylia.lib.util.showcase.PluginShowcases;
import net.exylia.lib.util.teleport.ExyliaLocation;
import net.exylia.lib.util.teleport.Teleports;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The admin screens every crate-backed cosmetic plugin shares: a hub, the
 * catalogue and one cosmetic, the players online and one player with their
 * keys and unlocks, and the showcases and crate blocks.
 *
 * <p>The screens are the library's own, compiled once into its menus from
 * {@code lang/<code>/menus/cosmetics/}; the buttons call one library action,
 * {@code exylialib:cosmetics <verb>}, and the session says which plugin's crate
 * it is about. Lists that change while open (players, unlocks, places) are
 * handed over as lambdas reading memory only.
 *
 * @param <T> the plugin's reward type
 */
public final class AdminScreens<T> {

    /**
     * What a plugin adds on top of its crate.
     *
     * @param permission what an admin needs
     * @param showcases  its showcases, or {@code null}
     * @param preview    plays a cosmetic for a viewer, or {@code null}
     * @param equipped   the id a player has on, from memory, or {@code null}
     * @param unequip    takes it off, on that player's thread, or {@code null}
     * @param favourites empties a player's favourites, on that player's thread, or {@code null}
     */
    public record Hooks(@NotNull String permission, @Nullable Places showcases,
                        @Nullable BiConsumer<Player, String> preview,
                        @Nullable Function<UUID, String> equipped,
                        @Nullable Consumer<Player> unequip,
                        @Nullable Consumer<Player> favourites) {
    }

    /**
     * A plugin's showcases, whichever class keeps them.
     *
     * @param all        every place, as stored
     * @param add        adds one where somebody stands
     * @param removeNear removes the one closest to a spot
     * @param clear      removes them all
     */
    public record Places(@NotNull Supplier<List<String>> all, @NotNull Consumer<Location> add,
                         @NotNull Consumer<Location> removeNear, @NotNull Runnable clear) {

        /** The library's own showcases. */
        public static @NotNull Places of(@NotNull PluginShowcases showcases) {
            return new Places(showcases::all, showcases::add, showcases::removeNear, showcases::clear);
        }
    }

    static final String FOLDER = "menus/cosmetics";
    static final String HUB = "cosmetics_admin";
    static final String CATALOGUE = "cosmetics_admin_catalogue";
    static final String REWARD = "cosmetics_admin_reward";
    static final String PLAYERS = "cosmetics_admin_players";
    static final String PLAYER = "cosmetics_admin_player";
    static final String UNLOCKS = "cosmetics_admin_unlocks";
    static final String SHOWCASES = "cosmetics_admin_showcases";
    static final String BLOCKS = "cosmetics_admin_blocks";
    static final List<String> SCREENS =
            List.of(HUB, CATALOGUE, REWARD, PLAYERS, PLAYER, UNLOCKS, SHOWCASES, BLOCKS);

    private static final String PLUGIN_KEY = "admin_plugin";
    private static final String TARGET_KEY = "admin_target";
    private static final String REWARD_KEY = "admin_reward";
    /** Arm's length plus a step, as the bind commands use. */
    private static final int REACH = 6;
    private static final int MAX_KEYS = 100_000;
    private static final String REFUSED = "[sound:ENTITY_VILLAGER_NO|0.7|1]";

    private static final Map<String, AdminScreens<?>> BY_PLUGIN = new ConcurrentHashMap<>();
    private static volatile PluginMenus menus;
    private static volatile Class<?> anchor = AdminScreens.class;

    private final Plugin plugin;
    private final PluginCrates crates;
    private final Prizes<T> prizes;
    private final PluginRewards rewards;
    private final Supplier<Hooks> hooks;

    public AdminScreens(@NotNull Plugin plugin, @NotNull PluginCrates crates, @NotNull Prizes<T> prizes,
                        @NotNull PluginRewards rewards, @NotNull Supplier<Hooks> hooks) {
        this.plugin = plugin;
        this.crates = crates;
        this.prizes = prizes;
        this.rewards = rewards;
        this.hooks = hooks;
    }

    // ------------------------------------------------------------------ library wiring

    /** Registers the one action behind every button. Once, before the screens are loaded. */
    public static void init(@NotNull Plugin library, @NotNull String namespace) {
        Actions.of(library, namespace).registerSync("cosmetics", AdminScreens::click);
    }

    /** Compiles the screens into the library's menus; admin screens are rewritten from the jar on every start. */
    public static void load(@NotNull PluginMenus target) {
        Plugin owner = target.plugin();
        Languages.replace(owner, anchor, FOLDER);
        for (String id : SCREENS) target.load(id, SettingsMenu.read(owner, FOLDER, id));
        menus = target;
    }

    /** For tests, whose classes and resources sit in different directories. */
    public static void anchor(@NotNull Class<?> packaged) {
        anchor = packaged;
    }

    /** Forgets the screens, when the library disables. */
    public static void release() {
        menus = null;
    }

    /** Makes this crate's screens reachable from the buttons. */
    public void register() {
        BY_PLUGIN.put(plugin.getName(), this);
    }

    /** Takes them away again, when the crate stops. */
    public void unregister() {
        BY_PLUGIN.remove(plugin.getName(), this);
    }

    // ------------------------------------------------------------------ screens

    /** The hub. On the viewer's thread. */
    public void open(@NotNull Player viewer) {
        Hooks now = hooks.get();
        Map<String, Object> context = base();
        context.put("admin_online", (Supplier<Integer>) () -> plugin.getServer().getOnlinePlayers().size());
        context.put("admin_showcases", (Supplier<Integer>) () -> now.showcases() == null ? 0 : now.showcases().all().get().size());
        context.put("admin_blocks", (Supplier<Integer>) () -> crates.boundBlocks().size());
        context.put("admin_has_showcases", now.showcases() != null);
        show(viewer, HUB, context, null);
    }

    private void catalogue(Player viewer) {
        CrateFaces<T> faces = faces(viewer);
        List<UiEntry> rows = new ArrayList<>();
        for (T reward : prizes.catalogue().all()) rows.add(faces.face(reward).build());
        show(viewer, CATALOGUE, base(), () -> rows);
    }

    private void reward(Player viewer, String id) {
        T reward = find(id);
        if (reward == null) {
            catalogue(viewer);
            return;
        }
        Map<String, Object> context = base();
        context.putAll(faces(viewer).face(reward).build().values());
        context.put(REWARD_KEY, prizes.idOf(reward));
        context.put("admin_has_token", prizes.catalogue().token(reward, viewer) != null);
        context.put("admin_has_preview", hooks.get().preview() != null);
        show(viewer, REWARD, context, null);
    }

    private void players(Player viewer) {
        show(viewer, PLAYERS, base(), () -> plugin.getServer().getOnlinePlayers().stream()
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(online -> UiEntry.of(online.getUniqueId())
                        .with("player_name", online.getName())
                        .with("player_keys", crates.keys(online.getUniqueId()))
                        .with("player_unlocked", crates.ownedCount(online))
                        .withFormatted("player_equipped", equipped(online.getUniqueId()))
                        .build())
                .toList());
    }

    private void player(Player viewer, UUID target) {
        Player online = plugin.getServer().getPlayer(target);
        if (online == null) {
            players(viewer);
            return;
        }
        Hooks now = hooks.get();
        Map<String, Object> context = base();
        context.put(TARGET_KEY, target.toString());
        context.put("player_name", online.getName());
        context.put("player_keys", (Supplier<Integer>) () -> crates.keys(target));
        context.put("player_unlocked", (Supplier<Integer>) () -> crates.ownedCount(online));
        context.put("player_equipped", (Supplier<String>) () -> equipped(target));
        context.put("admin_has_equipped", now.equipped() != null && now.unequip() != null);
        context.put("admin_has_favourites", now.favourites() != null);
        show(viewer, PLAYER, context, null);
    }

    private void unlocks(Player viewer, UUID target) {
        Player online = plugin.getServer().getPlayer(target);
        if (online == null) {
            players(viewer);
            return;
        }
        CrateFaces<T> faces = faces(viewer);
        CrateCatalogue<T> catalogue = prizes.catalogue();
        Map<String, Object> context = base();
        context.put(TARGET_KEY, target.toString());
        context.put("player_name", online.getName());
        show(viewer, UNLOCKS, context, () -> {
            List<UiEntry> rows = new ArrayList<>();
            for (T reward : catalogue.all()) {
                String id = prizes.idOf(reward);
                String state = crates.owns(target, id) ? "unlocked"
                        : catalogue.ownsOtherwise(online, reward) ? "permission" : null;
                rows.add(faces.face(reward).template(state).build());
            }
            return rows;
        });
    }

    private void places(Player viewer, boolean showcases) {
        Hooks now = hooks.get();
        if (showcases && now.showcases() == null) {
            open(viewer);
            return;
        }
        Supplier<List<String>> all = showcases ? now.showcases().all() : crates::boundBlocks;
        show(viewer, showcases ? SHOWCASES : BLOCKS, base(), () -> {
            List<String> entries = all.get();
            List<UiEntry> rows = new ArrayList<>(entries.size());
            for (int i = 0; i < entries.size(); i++) {
                ExyliaLocation at = location(entries.get(i));
                rows.add(UiEntry.of(entries.get(i))
                        .with("place_number", i + 1)
                        .with("place_world", at == null ? "?" : at.world())
                        .with("place_x", at == null ? "?" : (int) Math.floor(at.x()))
                        .with("place_y", at == null ? "?" : (int) Math.floor(at.y()))
                        .with("place_z", at == null ? "?" : (int) Math.floor(at.z()))
                        .with("place_server", at == null || at.server() == null ? "—" : at.server())
                        .build());
            }
            return rows;
        });
    }

    // ------------------------------------------------------------------ clicks

    private static ActionResult click(ActionContext context, ActionArguments arguments) {
        UiSession session = context.require(UiKeys.SESSION);
        AdminScreens<?> screens = session.context(PLUGIN_KEY, String.class).map(BY_PLUGIN::get).orElse(null);
        Player viewer = context.player();
        if (screens == null || !viewer.hasPermission(screens.hooks.get().permission())) {
            viewer.closeInventory();
            return ActionResult.success();
        }
        screens.click(viewer, session, context.get(UiKeys.ENTRY).orElse(null),
                arguments.string(0, "hub"), arguments.string(1, ""));
        return ActionResult.success();
    }

    private void click(Player viewer, UiSession session, @Nullable Object entry, String verb, String argument) {
        UUID target = session.context(TARGET_KEY, String.class).map(UUID::fromString).orElse(null);
        String reward = session.context(REWARD_KEY, String.class).orElse("");
        boolean showcases = session.menuId().endsWith(SHOWCASES);
        switch (verb) {
            case "catalogue" -> catalogue(viewer);
            case "reward" -> reward(viewer, argument);
            case "players" -> players(viewer);
            case "showcases" -> places(viewer, true);
            case "blocks" -> places(viewer, false);
            case "player" -> {
                if (entry instanceof UUID chosen) player(viewer, chosen);
                else if (target != null) player(viewer, target);
            }
            case "give_item" -> giveItem(viewer, reward);
            case "give_keys" -> giveKeys(viewer, argument, reward);
            case "preview" -> preview(viewer, reward);
            case "keys" -> {
                if (target != null) keys(viewer, target, argument);
            }
            case "unlocks" -> {
                if (target != null) unlocks(viewer, target);
            }
            case "toggle" -> {
                if (target != null) toggle(target, argument);
            }
            case "unequip" -> {
                Consumer<Player> unequip = hooks.get().unequip();
                if (target != null && unequip != null) {
                    confirm(viewer, Phrases.tr("{error}Take off what {0} has equipped?", name(target)),
                            () -> onTarget(target, unequip), () -> player(viewer, target));
                }
            }
            case "favourites" -> {
                Consumer<Player> favourites = hooks.get().favourites();
                if (target != null && favourites != null) {
                    confirm(viewer, Phrases.tr("{error}Clear every favourite of {0}?", name(target)),
                            () -> onTarget(target, favourites), () -> player(viewer, target));
                }
            }
            case "clear_unlocks" -> {
                if (target != null) {
                    confirm(viewer, Phrases.tr("{error}Take back every unlock of {0}?", name(target)),
                            () -> crates.clearUnlocks(target), () -> player(viewer, target));
                }
            }
            case "place_tp" -> {
                if (entry instanceof String place) {
                    viewer.closeInventory();
                    Teleports.of(plugin).to(viewer, place).warmup(0).start();
                }
            }
            case "place_remove" -> {
                if (entry instanceof String place) removePlace(viewer, place, showcases);
            }
            case "place_add" -> addPlace(viewer, showcases);
            case "place_clear" -> confirm(viewer, showcases
                            ? Phrases.tr("{error}Remove every showcase?")
                            : Phrases.tr("{error}Unbind every crate block?"),
                    () -> {
                        Places all = hooks.get().showcases();
                        if (!showcases) crates.clearBlocks();
                        else if (all != null) all.clear().run();
                    }, () -> places(viewer, showcases));
            default -> open(viewer);
        }
    }

    private void giveItem(Player viewer, String id) {
        T reward = find(id);
        ItemStack token = reward == null ? null : prizes.catalogue().token(reward, viewer);
        if (token != null) rewards.giveItem(viewer, token);
    }

    private void giveKeys(Player viewer, String how, String reward) {
        if (!how.equals("ask")) {
            crates.giveKeyItems(viewer, 1);
            return;
        }
        viewer.closeInventory();
        PluginInputs inputs = Inputs.of(plugin);
        inputs.answered(viewer, inputs.integer(viewer, Phrases.tr("{primary}&lKEY ITEMS TO RECEIVE"))
                        .range(1L, 2304L).defaultValue(1L).open(),
                amount -> {
                    crates.giveKeyItems(viewer, amount.intValue());
                    reward(viewer, reward);
                },
                () -> reward(viewer, reward));
    }

    private void preview(Player viewer, String id) {
        BiConsumer<Player, String> preview = hooks.get().preview();
        if (preview == null || find(id) == null) return;
        viewer.closeInventory();
        preview.accept(viewer, id);
    }

    private void keys(Player viewer, UUID target, String how) {
        if (!how.equals("ask")) {
            int step = how.startsWith("-") ? -1 : 1;
            if (step > 0 || crates.keys(target) > 0) crates.addKeys(target, step);
            return;
        }
        viewer.closeInventory();
        PluginInputs inputs = Inputs.of(plugin);
        inputs.answered(viewer, inputs.integer(viewer, Phrases.tr("{primary}&lCRATE KEYS {letters_black}» {highlight}{0}", name(target)))
                        .range(0L, (long) MAX_KEYS).defaultValue((long) crates.keys(target)).open(),
                amount -> {
                    crates.setKeys(target, amount.intValue());
                    player(viewer, target);
                },
                () -> player(viewer, target));
    }

    private void toggle(UUID target, String id) {
        if (find(id) == null) return;
        if (crates.owns(target, id)) crates.lock(target, id);
        else crates.unlock(target, id);
    }

    private void addPlace(Player viewer, boolean showcases) {
        if (showcases) {
            Places all = hooks.get().showcases();
            if (all != null) all.add().accept(viewer.getLocation());
            return;
        }
        Block block = viewer.getTargetBlockExact(REACH);
        if (block == null || block.getType().isAir()) {
            refuse(viewer, Phrases.tr("{error}Look at a block within six blocks first."));
            return;
        }
        if (!crates.bindBlock(block)) {
            refuse(viewer, Phrases.tr("{error}That block already opens the crate."));
        }
    }

    private void removePlace(Player viewer, String place, boolean showcases) {
        ExyliaLocation at = location(place);
        Location where = at == null ? null : at.toBukkitLocation();
        if (where == null) {
            refuse(viewer, Phrases.tr("{error}That place is not on this server."));
            return;
        }
        confirm(viewer, showcases ? Phrases.tr("{error}Remove this showcase?") : Phrases.tr("{error}Unbind this crate block?"),
                () -> {
                    Places all = hooks.get().showcases();
                    if (!showcases) crates.unbindBlock(where.getBlock());
                    else if (all != null) all.removeNear().accept(where);
                }, () -> places(viewer, showcases));
    }

    // ------------------------------------------------------------------ helpers

    /** Asks first, closing the window; either way the screen the admin came from comes back. */
    private void confirm(Player viewer, String prompt, Runnable change, Runnable back) {
        viewer.closeInventory();
        PluginInputs inputs = Inputs.of(plugin);
        inputs.answered(viewer, inputs.confirm(viewer, prompt).dangerous().open(), yes -> {
            if (yes) change.run();
            back.run();
        }, back);
    }

    /** Runs a plugin's change on the thread that owns the target, while they are here. */
    private void onTarget(UUID target, Consumer<Player> change) {
        Player online = plugin.getServer().getPlayer(target);
        if (online != null) net.exylia.lib.task.Tasks.of(plugin).runAtEntity(online, () -> change.accept(online));
    }

    private void show(Player viewer, String id, Map<String, Object> context,
                      @Nullable Supplier<? extends List<UiEntry>> rows) {
        PluginMenus current = menus;
        if (current == null || !viewer.isOnline()) return;
        if (rows == null) current.open(viewer, id, context);
        else current.open(viewer, id, context, rows);
    }

    private Map<String, Object> base() {
        Map<String, Object> context = new HashMap<>();
        context.put(PLUGIN_KEY, plugin.getName());
        context.put("admin_title", Values.inert(title(plugin.getName())));
        context.put("admin_catalogue", prizes.catalogue().all().size());
        return context;
    }

    private CrateFaces<T> faces(Player viewer) {
        return new CrateFaces<>(prizes.catalogue(), prizes.tiers(), viewer);
    }

    private @Nullable T find(String id) {
        String wanted = TierTable.normalise(id);
        for (T reward : prizes.catalogue().all()) {
            if (prizes.idOf(reward).equals(wanted)) return reward;
        }
        return null;
    }

    private String equipped(UUID player) {
        Function<UUID, String> equipped = hooks.get().equipped();
        String id = equipped == null ? null : equipped.apply(player);
        T reward = id == null ? null : find(id);
        return reward == null ? Phrases.tr("{muted}None") : prizes.catalogue().name(reward);
    }

    private String name(UUID player) {
        Player online = plugin.getServer().getPlayer(player);
        return online == null ? player.toString() : online.getName();
    }

    private void refuse(Player viewer, String line) {
        Text.from(plugin, REFUSED + "%prefix%" + line).send(viewer);
    }

    /** {@code ExyliaArrows} reads as {@code Arrows}. */
    static String title(String plugin) {
        return plugin.startsWith("Exylia") && plugin.length() > "Exylia".length()
                ? plugin.substring("Exylia".length()) : plugin;
    }

    private static @Nullable ExyliaLocation location(String entry) {
        try {
            return ExyliaLocation.fromString(entry);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
