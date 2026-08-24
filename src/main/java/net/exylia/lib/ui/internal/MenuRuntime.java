package net.exylia.lib.ui.internal;

import net.exylia.lib.action.ActionContext;
import net.exylia.lib.action.ActionSequence;
import net.exylia.lib.action.ActionStep;
import net.exylia.lib.action.Actions;
import net.exylia.lib.action.PluginActions;
import net.exylia.lib.effect.Effects;
import net.exylia.lib.item.Items;
import net.exylia.lib.item.PluginItems;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiKeys;
import net.exylia.lib.ui.UiSession;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The menus of one plugin, and the ones its players have open.
 *
 * <p>One of these per consumer, so releasing a plugin releases its menus and
 * nothing else. Sessions are found through the inventory holder rather than
 * from a map keyed by player: a player can have a chest open on top of a menu,
 * and only one of those two is ours.
 */
public final class MenuRuntime {

    /**
     * One runtime per consumer, shared by every {@code Menus.of} call.
     *
     * <p>Two calls in one plugin must see the same registered menus and the
     * same history; asking twice is what a plugin does when two classes both
     * need menus.
     */
    private static final Map<String, MenuRuntime> RUNTIMES = new ConcurrentHashMap<>();

    private final Plugin plugin;
    private final PluginItems items;

    /** The definitions this plugin registered, by id. */
    private final Map<String, UiDefinition> definitions = new ConcurrentHashMap<>();

    /** Where a player came from, so back can take them there. */
    private final Map<UUID, List<History>> history = new ConcurrentHashMap<>();

    /** Bumped every time somebody opens something, so stale work knows it is stale. */
    private final AtomicInteger generations = new AtomicInteger();

    private MenuRuntime(Plugin plugin) {
        this.plugin = plugin;
        this.items = Items.of(plugin);
    }

    /** The runtime belonging to a plugin, created on first ask. */
    public static MenuRuntime of(Plugin plugin) {
        return RUNTIMES.computeIfAbsent(plugin.getName(), ignored -> new MenuRuntime(plugin));
    }

    /**
     * The menu a player has open, whoever owns it.
     *
     * <p>Read off the window rather than searched for, so it costs the same
     * whether one plugin has menus or twenty.
     */
    public static @Nullable Session anySessionOf(Player viewer) {
        Inventory top = viewer.getOpenInventory().getTopInventory();
        return top.getHolder() instanceof MenuHolder holder ? holder.session() : null;
    }

    /**
     * Releases a plugin's menus.
     *
     * <p>Windows already on screen are closed: their buttons run actions from a
     * classloader that is going away, and a menu that answers a click with a
     * {@code NoClassDefFoundError} is worse than one that shut.
     */
    public static void release(String pluginName) {
        MenuRuntime runtime = RUNTIMES.remove(pluginName);
        if (runtime != null) {
            runtime.closeEverything();
        }
    }

    /** Releases every plugin's menus. */
    public static void releaseAll() {
        for (String name : List.copyOf(RUNTIMES.keySet())) {
            release(name);
        }
    }

    /** How many plugins have menus. */
    public static int count() {
        return RUNTIMES.size();
    }

    /** Forgets a player everywhere, on quit. */
    public static void forgetEverywhere(UUID id) {
        for (MenuRuntime runtime : RUNTIMES.values()) {
            runtime.forget(id);
        }
        Titles.forget(id);
    }

    /**
     * Starts watching which window each player has open.
     *
     * <p>Only needed to retitle one, so a server without PacketEvents simply
     * never retitles: every menu still opens, draws and clicks.
     */
    public static void init(Plugin plugin) {
        Titles.init(plugin);
    }

    public Plugin plugin() {
        return plugin;
    }

    /** Registers a compiled menu under its id. */
    public void register(UiDefinition definition) {
        definitions.put(definition.id(), definition);
    }

    /** A registered menu, or {@code null}. */
    public @Nullable UiDefinition definition(String id) {
        return definitions.get(id);
    }

    /** Every menu this plugin registered. */
    public Map<String, UiDefinition> definitions() {
        return Map.copyOf(definitions);
    }

    /** Forgets every registered menu, keeping open ones alive until they close. */
    public void clearDefinitions() {
        definitions.clear();
    }

    /**
     * Opens a menu.
     *
     * <p>Must run on the thread that owns the player; {@code Menus} is what
     * makes sure of that, so callers do not have to.
     *
     * @param viewer     who to show it to
     * @param definition what to show
     * @param context    values the menu is about
     * @return the session
     */
    public Session open(Player viewer, UiDefinition definition, Map<String, Object> context) {
        return open(viewer, definition, context, Map.of());
    }

    /**
     * Opens a menu whose lists are already filled.
     *
     * <p>The rows go in before the first draw rather than after it. A caller
     * that has them either handed them over a statement later — drawing every
     * list slot twice, once as the pagination filler and once for real — or
     * accepted that filler flashing on screen. Neither is necessary when the
     * rows were there all along.
     *
     * @param viewer     who to show it to
     * @param definition what to show
     * @param context    values the menu is about
     * @param sections   the rows of each list, by section id
     * @return the session
     */
    public Session open(Player viewer, UiDefinition definition, Map<String, Object> context,
                        Map<String, ? extends Collection<UiEntry>> sections) {
        Session previous = sessionOf(viewer);
        if (previous != null) {
            // Remember where they were, so back has somewhere to go. Recorded
            // before the new window replaces the old one, because opening one
            // inventory over another fires a close for the first.
            remember(viewer, previous);
        }

        MenuHolder holder = new MenuHolder();
        Inventory inventory = Session.inventoryFor(holder, definition, viewer, context, sections);
        Session session = new Session(this, viewer, definition, items, inventory,
                generations.incrementAndGet(), context);
        holder.bind(session, inventory);

        session.seed(sections);
        session.draw();
        viewer.openInventory(inventory);
        play(viewer, definition.sounds().open());

        List<List<Integer>> frames =
                OpenAnimation.frames(definition.openAnimation(), definition.size());
        // One frame is not an animation, and no frames means the file asked for
        // a shape nobody implemented. Either way the menu is already drawn.
        if (frames.size() > 1) {
            session.animate(frames, definition.openAnimation().speed());
        }

        session.startRefreshing();
        runOpenActions(session, viewer, definition);
        return session;
    }

    /**
     * Runs a menu's {@code open-actions}, if it has any.
     *
     * <p>After the window is on screen, not before: an action that closes the
     * menu, or opens another, has to have something to act on.
     */
    private void runOpenActions(Session session, Player viewer, UiDefinition definition) {
        if (definition.openActions().isEmpty()) {
            return;
        }
        PluginActions compiler = Actions.of(plugin);
        List<ActionStep> steps = new ArrayList<>(definition.openActions().size());
        for (String line : definition.openActions()) {
            steps.add(new ActionStep(
                    compiler.template(line).resolveOrNoop(viewer, session.context()), 0));
        }
        ActionContext context = ActionContext.forPlayer(viewer)
                .origin("menu")
                .put(UiKeys.SESSION, session)
                .put(UiKeys.MENU, definition.id())
                .build();
        session.cancelOnClose(ActionSequence.of(plugin, steps).execute(context));
    }

    /** The menu a player has open, if it is one of ours. */
    public @Nullable Session sessionOf(Player viewer) {
        Inventory top = viewer.getOpenInventory().getTopInventory();
        return top.getHolder() instanceof MenuHolder holder ? holder.session() : null;
    }

    /** The session behind an inventory, if it is one of ours. */
    static @Nullable Session sessionOf(Inventory inventory) {
        return inventory.getHolder() instanceof MenuHolder holder ? holder.session() : null;
    }

    /**
     * Takes a player back to where they were.
     *
     * @param viewer who to send back
     * @return whether there was anywhere to go
     */
    public boolean back(Player viewer) {
        List<History> stack = history.get(viewer.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            // Nowhere they have been, but the file may still say where this
            // menu belongs. A player sent straight to a submenu by a command
            // has no history and still expects back to mean something.
            return toParent(viewer);
        }
        History where = stack.removeLast();
        UiDefinition definition = definitions.get(where.menuId());
        if (definition == null) {
            return false;
        }
        Session session = open(viewer, definition, where.context());
        // open() pushed the screen they were leaving; going back is not a step
        // forward, so that push is undone.
        forgetLast(viewer);
        for (Map.Entry<String, Integer> page : where.pages().entrySet()) {
            session.page(page.getKey(), page.getValue());
        }
        play(viewer, definition.sounds().back());
        return true;
    }

    /**
     * Opens the menu a file names as this one's parent.
     *
     * <p>The fallback for a player with no history: {@code parent: main} says
     * where a menu belongs regardless of how somebody arrived at it.
     */
    private boolean toParent(Player viewer) {
        Session current = sessionOf(viewer);
        if (current == null) {
            return false;
        }
        String parent = current.definition().parent();
        if (parent == null || parent.isBlank()) {
            return false;
        }
        UiDefinition definition = definitions.get(parent);
        if (definition == null) {
            definition = definitions.get(qualify(parent));
        }
        if (definition == null) {
            return false;
        }
        open(viewer, definition, current.context());
        // Going up is not a step forward, so the screen open() recorded on the
        // way past is dropped.
        forgetLast(viewer);
        play(viewer, definition.sounds().back());
        return true;
    }

    /** A parent named without its namespace still means one of this plugin's. */
    private String qualify(String id) {
        if (id.indexOf(':') >= 0) {
            return id;
        }
        for (String known : definitions.keySet()) {
            int colon = known.indexOf(':');
            if (colon >= 0 && known.substring(colon + 1).equals(id)) {
                return known;
            }
        }
        return id;
    }

    /** Forgets where a player has been. */
    public void clearHistory(Player viewer) {
        history.remove(viewer.getUniqueId());
    }

    /** Called when a window of ours closes. */
    void closed(Session session) {
        session.released();
        play(session.viewer(), session.definition().sounds().close());
    }

    /** Forgets a player entirely, on quit. */
    public void forget(UUID id) {
        history.remove(id);
    }

    /** Plays one of a menu's sounds, if it has one. */
    void play(Player viewer, String sound) {
        if (sound == null || sound.isBlank()) {
            return;
        }
        Effects.soundFrom(sound).show(viewer);
    }

    /** Runs something on the thread that owns a player. */
    void atPlayer(Player viewer, Runnable action) {
        Tasks.of(plugin).runAtEntity(viewer, action);
    }

    /**
     * Runs something repeatedly beside a player.
     *
     * <p>An entity timer, so it stops on its own when the player leaves — an
     * animation revealing a menu nobody is looking at has nothing to reveal.
     *
     * @param viewer who it belongs to
     * @param period ticks between runs
     * @param work   what to do, given its own handle so it can stop
     * @return the handle
     */
    /** Runs something once, later, beside a player. */
    net.exylia.lib.task.TaskHandle later(Player viewer, long delay, Runnable work) {
        return Tasks.of(plugin).runAtEntityLater(viewer, delay, work);
    }

    net.exylia.lib.task.TaskHandle tick(Player viewer, long period,
                                        java.util.function.Consumer<net.exylia.lib.task.TaskHandle> work) {
        return Tasks.of(plugin).runAtEntityTimer(viewer, period, period, work);
    }

    PluginItems items() {
        return items;
    }

    private void remember(Player viewer, Session session) {
        Map<String, Integer> pages = new LinkedHashMap<>();
        for (String id : session.definition().sections().keySet()) {
            pages.put(id, session.page(id));
        }
        List<History> stack = history.computeIfAbsent(viewer.getUniqueId(),
                ignored -> new ArrayList<>());
        // Bounded: a player clicking between two menus for an hour must not
        // grow a list forever.
        if (stack.size() >= 16) {
            stack.removeFirst();
        }
        stack.add(new History(session.menuId(), session.context(), pages));
    }

    private void forgetLast(Player viewer) {
        List<History> stack = history.get(viewer.getUniqueId());
        if (stack != null && !stack.isEmpty()) {
            stack.removeLast();
        }
    }

    /**
     * A screen somebody was looking at.
     *
     * <p>The page is kept because returning to a list at page one is not
     * returning to it.
     */
    private record History(String menuId, Map<String, Object> context,
                           Map<String, Integer> pages) {
    }

    /** For diagnostics: how many players have somewhere to go back to. */
    public int trackedHistories() {
        return history.size();
    }

    /** Shuts every window of this plugin that is still on screen. */
    private void closeEverything() {
        definitions.clear();
        history.clear();
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            Session session = sessionOf(online);
            if (session != null && session.runtime() == this) {
                online.closeInventory();
            }
        }
    }

    /** The session a menu API call is about, as the public type. */
    public @Nullable UiSession publicSessionOf(Player viewer) {
        return sessionOf(viewer);
    }
}
