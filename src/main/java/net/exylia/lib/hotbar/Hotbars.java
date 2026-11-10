package net.exylia.lib.hotbar;

import net.exylia.lib.action.ActionCall;
import net.exylia.lib.action.ActionResult;
import net.exylia.lib.action.ActionSequence;
import net.exylia.lib.action.ActionStep;
import net.exylia.lib.action.ActionTemplate;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.command.Commands;
import net.exylia.lib.overlay.OverlayDefinition;
import net.exylia.lib.overlay.OverlayKeys;
import net.exylia.lib.overlay.Overlays;
import net.exylia.lib.overlay.PluginOverlays;
import net.exylia.lib.task.TaskHandle;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.ui.ClickBindings;
import net.exylia.lib.ui.ClickKind;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.util.Cooldowns;
import net.exylia.lib.util.PluginCooldowns;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * A lobby hotbar: named sets of buttons drawn in a player's inventory, each
 * with its slot, look, clicks, permission and cooldown, frozen in place.
 *
 * <pre>{@code
 * hotbars = Hotbars.of(this, actions)
 *         .register(HotbarSet.of("lobby", List.of(
 *                 HotbarItem.of("selector", 0, selectorItem)
 *                         .withActions(List.of("right: lobby:selector"))
 *                         .withCooldown(Duration.ofMillis(500)),
 *                 HotbarItem.of("cosmetics", 4, cosmeticsItem)
 *                         .withPermission("lobby.cosmetics", lockedCosmeticsItem)
 *                         .withCommands(List.of("right: player: cosmetics")))))
 *         .onCooldown((player, left) -> messages.send(player, "wait", left));
 *
 * hotbars.show(player, "lobby");   // entering the lobby
 * hotbars.hide(player);            // leaving it
 * }</pre>
 *
 * <h2>Built on Overlays</h2>
 * The buttons are an {@link Overlays overlay}: nothing is written into the
 * real inventory, so a crash or a disconnect can never leave a working button
 * behind as a real item, and the player's own items are where they were when
 * the hotbar comes off. Moving, dropping, swapping and picking the buttons out
 * are refused at packet level. It needs PacketEvents; without it
 * {@link #isAvailable()} is false and showing does nothing.
 *
 * <p>A button is drawn, not held: hitting something with one hits like a bare
 * hand, and using one never uses an item. Combat gear (a sword, a bow, a rod)
 * must be real items the plugin gives, never a hotbar button.
 *
 * <h2>Permissions</h2>
 * A button with a permission is shown only to holders; the others see its
 * {@code locked} look (pressing it does nothing but the refusal sound) or an
 * empty slot. Permissions are looked at again every two seconds for everybody
 * wearing a set, and at once on {@link #refresh(Player)}.
 *
 * <h2>Threads</h2>
 * Every method is safe from any thread. Presses run on the player's thread.
 *
 * @since 1.559.0
 */
public final class Hotbars {

    private static final Map<String, Hotbars> BY_PLUGIN = new ConcurrentHashMap<>();
    private static final String PRESS = "exylialib_hotbar_press";
    private static final long WATCH_TICKS = 40L;

    private final Plugin plugin;
    private final PluginActions actions;
    private final PluginOverlays overlays;
    private final PluginCooldowns cooldowns;
    private final Map<String, Compiled> sets = new ConcurrentHashMap<>();
    private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();
    private volatile @Nullable BiConsumer<Player, Duration> onCooldown;
    private volatile @Nullable TaskHandle watch;

    private record CompiledItem(HotbarItem item, ClickBindings bindings, UiItem drawn, @Nullable UiItem locked) {
    }

    private record Compiled(HotbarSet set, Map<String, CompiledItem> items) {
    }

    /** What a player wears: which set, which permissions it was drawn for, and the overlay itself. */
    private record Shown(String set, String mask, OverlayDefinition definition) {
    }

    private Hotbars(Plugin plugin, PluginActions actions) {
        this.plugin = plugin;
        this.actions = actions;
        this.overlays = Overlays.of(plugin);
        this.cooldowns = Cooldowns.forPlugin(plugin);
        actions.register(PRESS, this::press);
    }

    /**
     * A plugin's hotbars.
     *
     * @param plugin  the owner
     * @param actions the namespace the buttons' action lines are compiled in; the hotbar
     *                registers one action of its own there, {@code exylialib_hotbar_press}
     * @return the same instance for the same plugin until it disables
     */
    public static @NotNull Hotbars of(@NotNull Plugin plugin, @NotNull PluginActions actions) {
        return BY_PLUGIN.computeIfAbsent(plugin.getName(), name -> new Hotbars(plugin, actions));
    }

    /** Whether hotbars can be drawn on this server: PacketEvents is installed. */
    public static boolean isAvailable() {
        return Overlays.isAvailable();
    }

    /**
     * Adds a set, or replaces the one with the same name; players wearing it
     * are redrawn. Compiles every action line now, so a typo fails here.
     *
     * @throws IllegalArgumentException for an action that is not registered
     */
    public @NotNull Hotbars register(@NotNull HotbarSet set) {
        Map<String, CompiledItem> items = new LinkedHashMap<>();
        for (HotbarItem item : set.items()) items.put(item.id(), compile(set, item));
        sets.put(set.name(), new Compiled(set, Map.copyOf(items)));
        for (Map.Entry<UUID, Shown> entry : shown.entrySet()) {
            if (!entry.getValue().set().equals(set.name())) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) refresh(player);
        }
        return this;
    }

    /** The registered set by name. */
    public @NotNull Optional<HotbarSet> set(@NotNull String name) {
        return Optional.ofNullable(sets.get(name)).map(Compiled::set);
    }

    /** What a refused press for a cooldown says; nothing but the refusal sound by default. */
    public @NotNull Hotbars onCooldown(@Nullable BiConsumer<Player, Duration> onCooldown) {
        this.onCooldown = onCooldown;
        return this;
    }

    /**
     * Draws a set on a player, replacing whatever overlay they wore.
     *
     * @return {@code false} for a set that is not registered
     */
    public boolean show(@NotNull Player player, @NotNull String set) {
        if (!sets.containsKey(set)) return false;
        Tasks.of(plugin).runAtEntity(player, () -> draw(player, set, true));
        startWatching();
        return true;
    }

    /** Takes this plugin's hotbar off a player; the real inventory shows again. */
    public void hide(@NotNull Player player) {
        Shown was = shown.remove(player.getUniqueId());
        if (was != null && overlays.showing(player).filter(d -> d == was.definition()).isPresent()) {
            overlays.hide(player);
        }
    }

    /** The set a player wears from this plugin. */
    public @NotNull Optional<String> showing(@NotNull Player player) {
        return Optional.ofNullable(shown.get(player.getUniqueId())).map(Shown::set);
    }

    /** Redraws a player's set now: after a permission changed, or a button's state. */
    public void refresh(@NotNull Player player) {
        Shown current = shown.get(player.getUniqueId());
        if (current != null) Tasks.of(plugin).runAtEntity(player, () -> draw(player, current.set(), true));
    }

    /** {@link #refresh} for everybody wearing one of this plugin's sets. */
    public void refreshAll() {
        for (UUID id : shown.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) refresh(player);
        }
    }

    /** Drops a plugin's hotbars as it disables; its overlays come off with it. Called by the library. */
    @ApiStatus.Internal
    public static void release(@NotNull Plugin plugin) {
        Hotbars hotbars = BY_PLUGIN.remove(plugin.getName());
        if (hotbars == null) return;
        TaskHandle running = hotbars.watch;
        if (running != null) running.cancel();
        hotbars.shown.clear();
    }

    // ------------------------------------------------------------------ drawing

    /** On the player's thread. {@code force} redraws even when the permissions did not change. */
    private void draw(Player player, String setName, boolean force) {
        Compiled compiled = sets.get(setName);
        if (compiled == null || !player.isOnline()) {
            shown.remove(player.getUniqueId());
            return;
        }
        Shown before = shown.get(player.getUniqueId());
        String mask = mask(player, compiled);
        if (!force && before != null && before.mask().equals(mask)) return;
        OverlayDefinition.Builder overlay = OverlayDefinition.of(setName)
                .lock(compiled.set().lock())
                .pickup(compiled.set().pickup())
                .refresh(compiled.set().refresh());
        if (compiled.set().hideRest()) overlay.hideRest();
        for (CompiledItem item : compiled.items().values()) {
            UiItem drawn = holds(player, item.item()) ? item.drawn() : item.locked();
            if (drawn != null) overlay.slot(item.item().slot(), drawn);
        }
        OverlayDefinition definition = overlay.build();
        shown.put(player.getUniqueId(), new Shown(setName, mask, definition));
        overlays.show(player, definition);
    }

    private static boolean holds(Player player, HotbarItem item) {
        return item.permission() == null || player.hasPermission(item.permission());
    }

    /** Which buttons a player holds, as a string that changes when any of them does. */
    private static String mask(Player player, Compiled compiled) {
        StringBuilder out = new StringBuilder();
        for (CompiledItem item : compiled.items().values()) out.append(holds(player, item.item()) ? '1' : '0');
        return out.toString();
    }

    private synchronized void startWatching() {
        if (watch != null) return;
        watch = Tasks.of(plugin).runTimer(WATCH_TICKS, WATCH_TICKS, () -> {
            for (Map.Entry<UUID, Shown> entry : shown.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                Shown wearing = entry.getValue();
                if (player == null) {
                    shown.remove(entry.getKey(), wearing);
                    continue;
                }
                Tasks.of(plugin).runAtEntity(player, () -> {
                    // Another overlay took the place of this one: it is no longer ours to redraw.
                    if (overlays.showing(player).filter(d -> d == wearing.definition()).isEmpty()) {
                        shown.remove(player.getUniqueId(), wearing);
                        return;
                    }
                    draw(player, wearing.set(), false);
                });
            }
        });
    }

    // ------------------------------------------------------------------ pressing

    private CompiledItem compile(HotbarSet set, HotbarItem item) {
        ClickBindings.Builder lines = new ClickBindings.Builder();
        for (String line : item.actions()) lines.add(line, actions::template);
        for (String line : item.commands()) lines.addCommand(line);
        ClickBindings bindings = lines.build();
        Set<ClickKind> bound = EnumSet.noneOf(ClickKind.class);
        for (ClickKind kind : ClickKind.values()) {
            if (!bindings.forClick(kind).isEmpty() || !bindings.commandsForClick(kind).isEmpty()) bound.add(kind);
        }
        UiItem drawn = UiItem.of(item.item()).bindings(gate(bound, set.name() + " " + item.id())).build();
        UiItem locked = item.locked() == null ? null
                : UiItem.of(item.locked()).bindings(gate(EnumSet.allOf(ClickKind.class), "- - locked")).build();
        return new CompiledItem(item, bindings, drawn, locked);
    }

    /** One binding, the hotbar's own action, on every click the button answers. */
    private ClickBindings gate(Set<ClickKind> kinds, String arguments) {
        if (kinds.isEmpty()) return ClickBindings.none();
        ActionTemplate press = actions.template(PRESS + " " + arguments);
        return new ClickBindings.Builder().bind(kinds, press).build();
    }

    /** A button was pressed: the cooldown, then its commands and actions for that click. */
    private java.util.concurrent.CompletionStage<ActionResult> press(net.exylia.lib.action.ActionContext context,
                                                                     net.exylia.lib.action.ActionArguments arguments) {
        if (arguments.string(2, "").equals("locked")) return done(ActionResult.denied("locked"));
        Compiled compiled = sets.get(arguments.string(0, ""));
        CompiledItem item = compiled == null ? null : compiled.items().get(arguments.string(1, ""));
        if (item == null) return done(ActionResult.stop("no such hotbar item"));
        Player player = context.player();
        ClickKind kind = context.get(OverlayKeys.CLICK).orElse(ClickKind.RIGHT);
        Duration cooldown = item.item().cooldown();
        if (!cooldown.isZero()) {
            String key = "hotbar." + compiled.set().name() + "." + item.item().id();
            if (!cooldowns.tryStart(player, key, cooldown)) {
                BiConsumer<Player, Duration> hook = onCooldown;
                if (hook != null) hook.accept(player, cooldowns.remaining(player, key));
                return done(ActionResult.denied("cooldown"));
            }
        }
        var commands = item.bindings().commandsForClick(kind);
        if (!commands.isEmpty()) Commands.of(plugin).run(commands, player);
        List<ActionStep> steps = new ArrayList<>();
        for (ActionTemplate template : item.bindings().forClick(kind)) {
            ActionCall call = template.resolveOrNoop(player, Map.of());
            steps.add(new ActionStep(call, 0));
        }
        if (steps.isEmpty()) return done(ActionResult.success());
        return ActionSequence.of(plugin, steps).execute(context).result();
    }

    private static CompletableFuture<ActionResult> done(ActionResult result) {
        return CompletableFuture.completedFuture(Objects.requireNonNull(result));
    }
}
