package net.exylia.lib.ui.internal;

import net.exylia.lib.action.ActionExecution;
import net.exylia.lib.item.PluginItems;
import net.exylia.lib.text.Text;
import net.exylia.lib.ui.UiDefinition;
import net.exylia.lib.ui.UiEntry;
import net.exylia.lib.ui.UiItem;
import net.exylia.lib.ui.Pages;
import net.exylia.lib.ui.UiFillers;
import net.exylia.lib.ui.UiRefresh;
import net.exylia.lib.ui.UiSection;
import net.exylia.lib.ui.UiSession;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One open menu.
 *
 * <p>Holds three things and keeps them agreeing: what the definition says, what
 * each list currently contains, and what is drawn in every slot. The third is
 * the one clicks are checked against, because it is the only one that knows a
 * condition hid a button or a page moved a row.
 *
 * <p>Not thread-safe by design. Everything here touches an inventory, so it all
 * runs on the thread that owns the viewer; guarding it would be locks nobody
 * ever contends.
 */
final class Session implements UiSession {

    private final MenuRuntime runtime;
    private final Player viewer;
    private final UiDefinition definition;
    private final PluginItems items;
    private final Inventory inventory;
    private final int generation;

    /** What is drawn where, and what it came from. */
    private final Map<Integer, Rendered> slots = new HashMap<>();

    /** The rows of each list, by section id. */
    private final Map<String, List<UiEntry>> entries = new LinkedHashMap<>();

    /** Which page each list is showing, one-based. */
    private final Map<String, Integer> pages = new LinkedHashMap<>();

    /** Context keys this menu asked to be put back the next time it opens. */
    private final Set<String> remembered = new LinkedHashSet<>();

    /** Values the menu is about, also filled into everything it draws. */
    private final Map<String, Object> context = new LinkedHashMap<>();

    /**
     * Context keys whose letters reach the screen as written.
     *
     * <p>Replaced rather than added to, because what a slot was drawn with is
     * remembered by reference: a set that changed under it would make the
     * next redraw think nothing had.
     */
    private Set<String> verbatimContext = Set.of();

    /**
     * The context as text, read once per redraw rather than once per slot.
     *
     * <p>{@code null} when it has to be read again: the context changed, or a
     * redraw started and some of it is live.
     */
    private Map<String, String> contextText;

    /** Whether any context value is a lambda, read again on every redraw. */
    private boolean liveContext;

    /** Lists whose rows are asked for again on every timed redraw, by section id. */
    private final Map<String, Supplier<? extends Collection<UiEntry>>> sources = new LinkedHashMap<>();

    /** What each slot was last drawn from, so a redraw that changes nothing draws nothing. */
    private final DrawnSlots drawn = new DrawnSlots();

    /** A timed redraw that runs every second, for a menu whose file asked for none. */
    private static final UiRefresh LIVE = new UiRefresh(UiRefresh.Mode.SMART, 20, 0);

    /** What to stop when the menu closes. */
    private final List<ActionExecution> pending = new ArrayList<>();

    /** Two ticks: faster than anybody means to press a button twice. */
    private static final long PRESS_GAP_NANOS = 100_000_000L;

    /** When a button last ran, on the viewer's thread. */
    private long lastPress = System.nanoTime() - PRESS_GAP_NANOS;

    /** The title last sent, so an unchanged one costs no packet. */
    /** Built on first use; the definition it derives from cannot change. */
    private Set<Integer> inputSlots;

    private String lastTitle;

    private boolean open = true;

    /** Set while another menu replaces this one, so the swap makes no close sound. */
    private boolean silentClose;

    /** The redraw timer, when the menu asked for one. */
    private net.exylia.lib.task.TaskHandle refresher;

    /** Set once the menu is on screen, from when a timer may be started. */
    private boolean refreshing;

    Session(MenuRuntime runtime, Player viewer, UiDefinition definition, PluginItems items,
            Inventory inventory, int generation, Map<String, Object> context) {
        this.runtime = runtime;
        this.viewer = viewer;
        this.definition = definition;
        this.items = items;
        this.inventory = inventory;
        this.generation = generation;
        this.context.putAll(context);
        this.liveContext = anyLive(this.context);
        for (String id : definition.sections().keySet()) {
            entries.put(id, List.of());
            pages.put(id, 1);
        }
    }

    @Override
    public @NotNull Player viewer() {
        return viewer;
    }

    @Override
    public @NotNull String menuId() {
        return definition.id();
    }

    @Override
    public @NotNull UiDefinition definition() {
        return definition;
    }

    @Override
    public @NotNull Inventory inventory() {
        return inventory;
    }

    // ---------------------------------------------------------------- paging

    @Override
    public int page(@NotNull String section) {
        return pages.getOrDefault(section, 1);
    }

    @Override
    public int pages(@NotNull String section) {
        UiSection list = definition.section(section);
        return list == null ? 1 : list.pagesFor(entries.getOrDefault(section, List.of()).size());
    }

    @Override
    public boolean page(@NotNull String section, int page) {
        UiSection list = definition.section(section);
        if (list == null) {
            return false;
        }
        // Clamped rather than refused: a list that lost rows while somebody was
        // reading the last page shows the last page that exists.
        int wanted = Pages.clamp(page, entries(section).size(), list.perPage());
        if (wanted == page(section)) {
            return false;
        }
        pages.put(section, wanted);
        reread();
        drawSection(list);
        retitle();
        return true;
    }

    @Override
    public boolean turn(@NotNull String section, int step) {
        return page(section, page(section) + step);
    }

    @Override
    public boolean page(int page) {
        UiSection only = definition.section();
        return only != null && page(only.id(), page);
    }

    @Override
    public boolean nextPage() {
        UiSection only = definition.section();
        return only != null && turn(only.id(), 1);
    }

    @Override
    public boolean previousPage() {
        UiSection only = definition.section();
        return only != null && turn(only.id(), -1);
    }

    // --------------------------------------------------------------- entries

    @Override
    public @NotNull List<UiEntry> entries(@NotNull String section) {
        return entries.getOrDefault(section, List.of());
    }

    @Override
    public void entries(@NotNull String section, @NotNull Collection<UiEntry> rows) {
        UiSection list = definition.section(section);
        if (list == null) {
            return;
        }
        // Rows handed over replace a list that was being asked for its rows.
        sources.remove(section);
        entries.put(section, List.copyOf(rows));
        // Keep the reader where they were, as far as there is still a page
        // there. A leaderboard refreshing under somebody on page three leaves
        // them on page three.
        pages.put(section, Pages.clamp(page(section), rows.size(), list.perPage()));
        reread();
        drawSection(list);
        // The count is part of the title, and it just changed: a menu filled
        // after it opened would otherwise say "1/1" over five pages of rows.
        retitle();
        ensureRefreshing();
    }

    @Override
    public void entries(@NotNull Collection<UiEntry> rows) {
        UiSection only = definition.section();
        if (only != null) {
            entries(only.id(), rows);
        }
    }

    @Override
    public void entries(@NotNull String section, @NotNull Supplier<? extends Collection<UiEntry>> rows) {
        if (definition.section(section) == null) {
            return;
        }
        Collection<UiEntry> read = rows.get();
        entries(section, read == null ? List.of() : read);
        follow(section, rows);
    }

    @Override
    public void entries(@NotNull Supplier<? extends Collection<UiEntry>> rows) {
        UiSection only = definition.section();
        if (only != null) {
            entries(only.id(), rows);
        }
    }

    /**
     * Asks a list for its rows again on every timed redraw, from now on.
     *
     * <p>Without drawing: for a menu that was just opened with the rows this
     * returned a moment ago.
     */
    void follow(String section, Supplier<? extends Collection<UiEntry>> rows) {
        if (definition.section(section) != null) {
            sources.put(section, rows);
            ensureRefreshing();
        }
    }

    /**
     * Fills the lists before the menu has been drawn once.
     *
     * <p>{@link #entries(String, Collection)} without the redraw or the
     * retitle, for the only moment neither is needed: between building the
     * session and its first {@code draw}. A menu opened by a caller that
     * already has its rows drew every list slot twice otherwise — once as the
     * pagination filler, and again over the top of it a statement later — and
     * an item is not cheap to render.
     *
     * <p>The title is recorded rather than sent, because the window was
     * created with the page count these rows imply. Sending it again would be
     * a packet saying what the client already has.
     */
    void seed(Map<String, ? extends Collection<UiEntry>> sections) {
        for (Map.Entry<String, ? extends Collection<UiEntry>> section : sections.entrySet()) {
            UiSection list = definition.section(section.getKey());
            if (list == null) {
                continue;
            }
            List<UiEntry> rows = List.copyOf(section.getValue());
            entries.put(section.getKey(), rows);
            pages.put(section.getKey(), Pages.clamp(page(section.getKey()), rows.size(),
                    list.perPage()));
        }
        UiSection only = definition.section();
        if (only != null && namesAPage(definition.title())) {
            lastTitle = filledTitle(definition.title(), context, page(only.id()),
                    only.pagesFor(entries(only.id()).size()));
        }
    }

    @Override
    public @NotNull Optional<UiEntry> entryAt(int slot) {
        Rendered rendered = slots.get(slot);
        return rendered == null ? Optional.empty() : Optional.ofNullable(rendered.entry());
    }

    // ------------------------------------------------------------- redrawing

    @Override
    public int invalidate(@NotNull String... dependencies) {
        if (dependencies.length == 0) {
            return 0;
        }
        Set<String> changed = Set.of(dependencies);
        reread();
        int redrawn = 0;
        for (Map.Entry<Integer, UiItem> fixed : definition.items().entrySet()) {
            if (dependsOnAny(fixed.getValue(), changed)) {
                // Asked for by name, so drawn whatever the cache thinks: the
                // plugin knows something changed that no value shows.
                drawn.forget(fixed.getKey());
                drawFixed(fixed.getKey(), fixed.getValue());
                redrawn++;
            }
        }
        for (UiSection list : definition.sections().values()) {
            UiItem template = list.template(null);
            if (template != null && dependsOnAny(template, changed)) {
                list.slots().forEach(drawn::forget);
                drawSection(list);
                redrawn += list.slots().size();
            }
        }
        return redrawn;
    }

    private static boolean dependsOnAny(UiItem item, Set<String> changed) {
        for (String dependency : item.dependencies()) {
            if (changed.contains(dependency)) {
                return true;
            }
        }
        for (UiItem alternate : item.alternates()) {
            if (dependsOnAny(alternate, changed)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean invalidateSlot(int slot) {
        reread();
        drawn.forget(slot);
        UiItem fixed = definition.items().get(slot);
        if (fixed != null) {
            drawFixed(slot, fixed);
            return true;
        }
        UiSection list = definition.sectionAt(slot);
        if (list == null) {
            return false;
        }
        drawSection(list);
        return true;
    }

    @Override
    public void refresh() {
        draw();
    }

    @Override
    public void refreshFixed() {
        reread();
        drawn.clear();
        drawFillers();
        for (Map.Entry<Integer, UiItem> fixed : definition.items().entrySet()) {
            drawFixed(fixed.getKey(), fixed.getValue());
        }
    }

    /**
     * Redraws what a timed refresh should redraw.
     *
     * <p>{@code FULL} redraws everything. {@code SMART} redraws only the slots
     * that can actually differ from what is already on screen — a menu of
     * decorations on a twenty-tick timer should cost nothing, and redrawing a
     * static slot every second is packets for an identical item.
     *
     * <p>Either way, a slot whose definition and values came out exactly as
     * last time is not rendered again; see {@link DrawnSlots}. That is what
     * makes a list of forty rows with one countdown cost one render a second.
     */
    private void tickRefresh() {
        if (!isOpen()) {
            return;
        }
        reread();
        boolean pulled = pull();
        if (refreshPolicy().mode() == UiRefresh.Mode.FULL) {
            draw();
            if (pulled) {
                retitle();
            }
            return;
        }
        for (Map.Entry<Integer, UiItem> fixed : definition.items().entrySet()) {
            if (fixed.getValue().isDynamic()) {
                drawFixed(fixed.getKey(), fixed.getValue());
            }
        }
        for (UiSection list : definition.sections().values()) {
            UiItem template = list.template(null);
            if ((template != null && template.isDynamic()) || !entries(list.id()).isEmpty()
                    || sources.containsKey(list.id())) {
                // A followed list was just built, live values and all.
                drawSection(list, !sources.containsKey(list.id()));
            }
        }
        if (pulled) {
            retitle();
        }
    }

    /**
     * Asks every followed list for its rows again.
     *
     * @return whether there was any to ask
     */
    private boolean pull() {
        if (sources.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Supplier<? extends Collection<UiEntry>>> source : sources.entrySet()) {
            UiSection list = definition.section(source.getKey());
            Collection<UiEntry> rows = source.getValue().get();
            List<UiEntry> now = rows == null ? List.of() : List.copyOf(rows);
            entries.put(source.getKey(), now);
            // A reader on page three stays there while it exists; a list that
            // shrank under them lands them on its last page.
            pages.put(source.getKey(), Pages.clamp(page(source.getKey()), now.size(), list.perPage()));
        }
        return true;
    }

    /**
     * When this menu redraws.
     *
     * <p>What the file says, except that a menu whose file says nothing and
     * which holds something live redraws every second. A plugin that hands
     * over a countdown means for it to count; it should not also have to
     * find every server's copy of the file and add a {@code refresh} to it.
     * A file that writes {@code mode: DISABLED} is obeyed.
     */
    UiRefresh refreshPolicy() {
        UiRefresh written = definition.refresh();
        // Identity on purpose: NEVER is what a file without the block reads
        // as, and an explicit DISABLED is a different instance.
        if (written != UiRefresh.NEVER || !hasLive()) {
            return written;
        }
        return LIVE;
    }

    /** Returns whether anything in this menu is read again on a redraw. */
    private boolean hasLive() {
        if (liveContext || !sources.isEmpty()) {
            return true;
        }
        for (List<UiEntry> rows : entries.values()) {
            for (UiEntry row : rows) {
                if (row.isLive()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Starts the redraw timer, if the menu asked for one.
     *
     * <p>An entity timer, so it dies with the player. Started only when there
     * is something that could change: a static menu on a {@code SMART} timer
     * would wake up every second to decide it had nothing to do.
     */
    void startRefreshing() {
        refreshing = true;
        ensureRefreshing();
    }

    /**
     * Starts the timer if the menu now needs one and has none.
     *
     * <p>Asked again whenever something live arrives after the menu opened —
     * rows handed over later, a lambda put into the context.
     */
    private void ensureRefreshing() {
        if (!refreshing || refresher != null || !open) {
            return;
        }
        UiRefresh policy = refreshPolicy();
        if (!policy.isTimed() || !(definition.isDynamic() || hasLive())) {
            return;
        }
        refresher = runtime.tick(viewer, policy.interval(), handle -> {
            if (!isOpen()) {
                handle.cancel();
                refresher = null;
                return;
            }
            tickRefresh();
        });
    }

    /**
     * Redraws what a click changed, shortly after.
     *
     * <p>What {@code ON_CLICK} means: the action behind a button has not
     * necessarily finished when the click handler returns, so the redraw waits
     * the delay the file asked for.
     *
     * <p>Everything that can change is redrawn, not only the slot that was
     * clicked. A button rarely changes just itself: adding a layer moves a
     * counter, a preview and a list, and none of those is the slot the click
     * landed on. Redrawing one slot left the rest showing what they said before
     * the click, which reads as a menu that needs clicking twice.
     *
     * <p>A redraw the plugin did in the meantime is not undone, because this
     * draws from the same context the plugin just changed rather than from a
     * copy taken when the click arrived.
     *
     * @param slot which slot was clicked
     */
    void refreshAfterClick(int slot) {
        UiRefresh policy = refreshPolicy();
        if (!policy.isOnClick()) {
            return;
        }
        int delay = policy.clickDelay();
        if (delay <= 0) {
            redrawChangeable(slot);
            return;
        }
        runtime.later(viewer, delay, () -> {
            if (isOpen()) {
                redrawChangeable(slot);
            }
        });
    }

    /**
     * Redraws every slot whose contents can differ from what is on screen.
     *
     * <p>The clicked slot always counts: a button drawn from nothing but
     * literal text still has to come back after a condition stopped passing.
     */
    private void redrawChangeable(int clicked) {
        reread();
        for (Map.Entry<Integer, UiItem> fixed : definition.items().entrySet()) {
            if (fixed.getValue().isDynamic() || fixed.getKey() == clicked) {
                if (fixed.getKey() == clicked) {
                    drawn.forget(clicked);
                }
                drawFixed(fixed.getKey(), fixed.getValue());
            }
        }
        for (UiSection list : definition.sections().values()) {
            drawSection(list);
        }
    }

    // ---------------------------------------------------------------- context

    @Override
    public @NotNull Map<String, Object> context() {
        return Map.copyOf(context);
    }

    @Override
    public <T> @NotNull Optional<T> context(@NotNull String key, @NotNull Class<T> type) {
        Object value = context.get(key);
        // A live value is asked for as what it reads, unless the caller wants
        // the lambda itself.
        if (!type.isInstance(value) && value instanceof Supplier<?> reader) {
            value = reader.get();
        }
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    @Override
    public @NotNull UiSession context(@NotNull String key, @NotNull Object value) {
        context.put(key, value);
        liveContext = anyLive(context);
        contextText = null;
        ensureRefreshing();
        return this;
    }

    /**
     * The context with its live values read, for an action or a command.
     *
     * <p>Everything else is handed over as it was put: a handler may read a
     * value back as the object it is.
     */
    Map<String, Object> contextValues() {
        if (!liveContext) {
            return context();
        }
        Map<String, Object> read = new HashMap<>(context.size());
        context.forEach((key, value) -> read.put(key, value instanceof Supplier<?> reader
                ? text(reader) : value));
        return read;
    }

    private static boolean anyLive(Map<String, Object> context) {
        for (Object value : context.values()) {
            if (value instanceof Supplier<?>) {
                return true;
            }
        }
        return false;
    }

    /**
     * The context as text, with its live values read.
     *
     * <p>Read once per redraw however many slots it fills: a lambda behind a
     * countdown shown on six buttons is called once, not six times.
     */
    private Map<String, String> contextText() {
        Map<String, String> text = contextText;
        if (text == null) {
            text = context.isEmpty() ? Map.of() : merged(context, Map.of());
            contextText = text;
        }
        return text;
    }

    /** Marks the start of a redraw: live context values are read again. */
    private void reread() {
        if (liveContext) {
            contextText = null;
        }
    }

    // ----------------------------------------------------------------- input

    @Override
    public @NotNull Set<Integer> inputSlots() {
        // Every click and every drag asks for this, and the answer is fixed for the
        // whole session: the definition is immutable and its slots never move.
        Set<Integer> cached = inputSlots;
        if (cached == null) {
            cached = Set.copyOf(definition.inputSlots());
            inputSlots = cached;
        }
        return cached;
    }

    @Override
    public @NotNull Map<Integer, ItemStack> inputs() {
        Map<Integer, ItemStack> left = new LinkedHashMap<>();
        for (int slot : definition.inputSlots()) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) {
                left.put(slot, item);
            }
        }
        return left;
    }

    @Override
    public void input(int slot, ItemStack item) {
        requireInput(slot);
        inventory.setItem(slot, item);
    }

    @Override
    public void inputs(@NotNull Map<Integer, ItemStack> items) {
        java.util.Objects.requireNonNull(items, "items");
        // Checked in full first: a layout that arrives half-written is one the
        // player cannot tell apart from the one they meant to load.
        for (Integer slot : items.keySet()) {
            requireInput(slot == null ? -1 : slot);
        }
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            inventory.setItem(entry.getKey(), entry.getValue());
        }
    }

    private void requireInput(int slot) {
        if (!inputSlots().contains(slot)) {
            throw new IllegalArgumentException("Slot " + slot + " is not an input slot of menu \""
                    + definition.id() + "\"; its input slots are " + inputSlots());
        }
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    public void cancelOnClose(@NotNull ActionExecution execution) {
        if (!open) {
            // The menu closed while this was being started. Nothing should
            // outlive a screen nobody is looking at.
            execution.cancel("menu closed");
            return;
        }
        // Finished ones go first: a button clicked all evening must not keep
        // every execution it ever started alive until the menu closes.
        pending.removeIf(ActionExecution::isDone);
        pending.add(execution);
    }

    @Override
    public void close() {
        viewer.closeInventory();
    }

    @Override
    public boolean isOpen() {
        return open && runtime.sessionOf(viewer) == this;
    }

    @Override
    public int generation() {
        return generation;
    }

    // ------------------------------------------------------------- internals

    /**
     * Whether a button may run now, claiming the press when it may.
     *
     * <p>Every press runs actions, maybe commands, a sound and a redraw, and a
     * macro clicks far faster than a hand: a command run this way skips the
     * game's own chat spam kick. Presses closer together than two ticks are
     * dropped without a word.
     */
    boolean claimPress() {
        long now = System.nanoTime();
        if (now - lastPress < PRESS_GAP_NANOS) {
            return false;
        }
        lastPress = now;
        return true;
    }

    /** The runtime that owns this menu. */
    MenuRuntime runtime() {
        return runtime;
    }

    @Override
    public void remember(@NotNull String... keys) {
        remembered.addAll(Set.of(keys));
    }

    @Override
    public void verbatim(@NotNull String... keys) {
        Set<String> all = new LinkedHashSet<>(verbatimContext);
        all.addAll(Set.of(keys));
        verbatimContext = Set.copyOf(all);
    }

    /**
     * The context values this menu asked to be put back, and nothing else.
     *
     * <p>Most of the context is derived — a status line, a name, a count — and
     * restoring it would show a player yesterday's numbers until the first
     * redraw. Only what the menu named is worth keeping.
     */
    Map<String, Object> contextSnapshot() {
        Map<String, Object> kept = new LinkedHashMap<>();
        for (String key : remembered) {
            Object value = context.get(key);
            if (value != null) kept.put(key, value);
        }
        return Map.copyOf(kept);
    }

    /** Where each list is, for a runtime that wants to put it back later. */
    Map<String, Integer> pageSnapshot() {
        return Map.copyOf(pages);
    }

    /**
     * Puts the lists back where they were.
     *
     * <p>Called before the first seed, which clamps whatever is here against
     * the rows the menu actually has: a remembered page five of a list that is
     * now two pages long lands on two rather than on nothing.
     */
    void restorePages(Map<String, Integer> remembered) {
        remembered.forEach((section, page) -> {
            if (page > 1 && definition.section(section) != null) {
                pages.put(section, page);
            }
        });
    }

    /** What is drawn in a slot, for the click handler. */
    @Nullable Rendered renderedAt(int slot) {
        return slots.get(slot);
    }

    void silenceClose(boolean silent) {
        silentClose = silent;
    }

    boolean closeSilenced() {
        return silentClose;
    }

    /** Stops everything this menu started. Called once, when it closes. */
    void released() {
        open = false;
        if (refresher != null) {
            refresher.cancel();
            refresher = null;
        }
        for (ActionExecution execution : pending) {
            execution.cancel("menu closed");
        }
        pending.clear();
    }

    /** Draws the whole menu. */
    void draw() {
        slots.clear();
        drawn.clear();
        reread();
        inventory.clear();
        drawFillers();
        for (Map.Entry<Integer, UiItem> fixed : definition.items().entrySet()) {
            drawFixed(fixed.getKey(), fixed.getValue());
        }
        for (UiSection list : definition.sections().values()) {
            drawSection(list);
        }
    }

    /** Writes one slot something other than a definition decided. */
    private void put(int slot, ItemStack item) {
        inventory.setItem(slot, item);
        drawn.forget(slot);
    }

    /** Draws a definition into a slot with nothing but the menu's context. */
    private void paint(int slot, UiItem item) {
        paint(slot, item, Map.of(), Set.of(), Set.of());
    }

    /**
     * Draws a definition into a slot, unless it would come out as what is
     * already there.
     *
     * <p>Every drawing path that renders goes through here, which is what
     * lets a redraw that changes nothing render nothing.
     *
     * <p>Context values are parsed; row values are literal unless the caller
     * asked otherwise. The two are not the same kind of thing. A row value is
     * one entry in a list, and lists are full of names players chose, so
     * inserting them as text is what stops somebody called {@code <rainbow>}
     * from repainting the menu. A context value describes the whole screen and
     * is written by whoever wrote the menu — the same person who wrote the
     * template it lands in, and in the same file.
     */
    private void paint(int slot, UiItem item, Map<String, String> values, Set<String> formatted,
                       Set<String> verbatim) {
        paint(slot, item, values, formatted, verbatim, null);
    }

    /**
     * The same, reusing an item already rendered from the same definition and
     * values: a background painted into forty slots is rendered once.
     *
     * @param shared what this definition rendered to a moment ago, or {@code null}
     * @return what the slot now holds when it was rendered or reused here,
     *         {@code shared} when the slot was left as it was
     */
    private ItemStack paint(int slot, UiItem item, Map<String, String> values, Set<String> formatted,
                            Set<String> verbatim, @Nullable ItemStack shared) {
        Map<String, String> context = contextText();
        Map<String, String> all = context.isEmpty() ? values : merged(context, values);
        Set<String> parsed = context.isEmpty() ? formatted : parsed(context, values, formatted);
        Set<String> kept = verbatim(verbatim);
        if (drawn.unchanged(slot, item.item(), all, parsed, kept)) {
            return shared;
        }
        ItemStack rendered = shared != null ? shared : items.renderIcon(item.item(), viewer, all, parsed, kept);
        inventory.setItem(slot, rendered);
        drawn.record(slot, item.item(), all, parsed, kept);
        return rendered;
    }

    /**
     * Fills every slot the menu does not otherwise use.
     *
     * <p>Before anything else, so a fixed item or a list draws over it. Slots a
     * list owns are skipped: an empty one is that list's filler, which is not
     * necessarily the menu's.
     */
    private void drawFillers() {
        UiFillers fillers = definition.fillers();
        if (fillers.isEmpty()) {
            return;
        }
        Set<Integer> reserved = new LinkedHashSet<>(definition.items().keySet());
        reserved.addAll(definition.inputSlots());
        for (UiSection list : definition.sections().values()) {
            reserved.addAll(list.slots());
            // A page arrow's slot is the list's too, drawn or not: the list
            // itself puts the background back when the arrow has nowhere to
            // go, so painting glass over it here only wipes a live button on
            // a redraw that leaves the lists alone.
            if (list.previous() != null) reserved.add(list.previous().slot());
            if (list.next() != null) reserved.add(list.next().slot());
        }

        // Named panels first, so the background does not paint over them, and
        // in file order so the first to claim a slot keeps it.
        for (UiFillers.Panel panel : fillers.custom()) {
            ItemStack shared = null;
            for (int slot : panel.slots()) {
                if (slot < 0 || slot >= definition.size() || reserved.contains(slot)) {
                    continue;
                }
                shared = paint(slot, panel.item(), Map.of(), Set.of(), Set.of(), shared);
                slots.put(slot, Rendered.FILLER);
                reserved.add(slot);
            }
        }

        if (fillers.global() == null) {
            return;
        }
        ItemStack background = null;
        for (int slot = 0; slot < definition.size(); slot++) {
            if (reserved.contains(slot)) {
                continue;
            }
            background = paint(slot, fillers.global(), Map.of(), Set.of(), Set.of(), background);
            slots.put(slot, Rendered.FILLER);
        }
    }

    /**
     * Draws one fixed slot.
     *
     * <p>A slot whose condition fails is not merely blank: it is not there.
     * Nothing is recorded for it, so a click on it finds nothing and does
     * nothing, which is the same answer as clicking the background. The
     * background is also what is drawn in its place, so a hidden button
     * leaves a hole in the glass rather than a hole in the menu.
     *
     * <p>A slot the file wrote twice is one button in two states, and the
     * first state whose condition passes is the one that is there.
     */
    private void drawFixed(int slot, UiItem item) {
        UiItem visible = item.visible(state -> passes(state, Map.of())).orElse(null);
        if (visible == null) {
            UiItem background = definition.fillers().backgroundAt(slot);
            if (background == null) {
                put(slot, null);
                slots.remove(slot);
            } else {
                paint(slot, background);
                slots.put(slot, Rendered.FILLER);
            }
            return;
        }
        paint(slot, visible);
        slots.put(slot, Rendered.of(visible));
    }

    /** Draws one list at its current page. */
    private void drawSection(UiSection list) {
        drawSection(list, true);
    }

    /**
     * Draws one list at its current page.
     *
     * @param freshen whether to read the live values of the rows on it again
     */
    private void drawSection(UiSection list, boolean freshen) {
        int page = Pages.clamp(page(list.id()), entries(list.id()).size(), list.perPage());
        pages.put(list.id(), page);
        // A page somebody turns to shows its countdowns as they are now, not
        // as they were when the menu opened.
        if (freshen) {
            freshen(list);
        }
        List<UiEntry> rows = entries(list.id());

        int first = Pages.indexOf(page, list.perPage(), 0);
        List<Integer> where = list.slots();
        for (int index = 0; index < where.size(); index++) {
            int slot = where.get(index);
            int entryIndex = first + index;
            if (entryIndex >= rows.size()) {
                drawSectionFiller(list, slot);
                continue;
            }
            drawRow(list, slot, rows.get(entryIndex));
        }
        drawNavigation(list, rows.size());
    }

    /** Draws one row of a list into its slot. */
    private void drawRow(UiSection list, int slot, UiEntry entry) {
        UiItem template = list.template(entry.template());

        // A row that brought its own item. There is no template to render
        // and none to take a condition or click bindings from, so the item
        // is drawn as given and the row's value is what a click reads.
        if (entry.hasItem()) {
            put(slot, entry.item());
            slots.put(slot, Rendered.of(template, entry, list.id()));
            return;
        }
        if (template == null || !passes(template, entry.values())) {
            drawSectionFiller(list, slot);
            return;
        }
        paint(slot, template, entry.values(), entry.formatted(), entry.verbatim());
        slots.put(slot, Rendered.of(template, entry, list.id()));
    }

    /**
     * Reads the live values of the rows on the page a list shows again.
     *
     * <p>Only that page: a row nobody can see is read when it comes into view.
     * The rows that moved replace the old ones, so a click reads what is on
     * screen.
     */
    private void freshen(UiSection list) {
        List<UiEntry> rows = entries(list.id());
        int first = Pages.indexOf(page(list.id()), list.perPage(), 0);
        int last = Math.min(rows.size(), first + list.slots().size());
        List<UiEntry> now = null;
        for (int index = first; index < last; index++) {
            UiEntry row = rows.get(index);
            UiEntry fresh = row.refreshed();
            if (fresh == row) {
                continue;
            }
            if (now == null) {
                now = new ArrayList<>(rows);
            }
            now.set(index, fresh);
        }
        if (now != null) {
            entries.put(list.id(), List.copyOf(now));
        }
    }

    /**
     * Fills a slot of a list that has no row for it.
     *
     * <p>The section's own filler first, then the menu's {@code pagination}
     * filler, which is what tells somebody with an empty list why it is empty —
     * "no kits available" rather than a grey pane. Four hundred and ninety-nine
     * deployed menus write one.
     */
    private void drawSectionFiller(UiSection list, int slot) {
        UiItem filler = list.filler() != null
                ? list.filler()
                : definition.fillers().pagination();
        if (filler == null) {
            put(slot, null);
            slots.remove(slot);
            return;
        }
        paint(slot, filler);
        slots.put(slot, Rendered.FILLER);
    }

    /**
     * Draws a list's page buttons.
     *
     * <p>They carry the page numbers, so they are re-drawn whenever the page
     * moves — a button reading "Page 2/5" that still says 1/5 is worse than no
     * button at all.
     *
     * <p>A button with nowhere to go is not drawn at all. An arrow that is
     * there and does nothing is the same lie in every menu that has fewer rows
     * than one page, which is most of them on a quiet server.
     */
    private void drawNavigation(UiSection list, int rows) {
        int page = page(list.id());
        int pages = list.pagesFor(rows);
        Map<String, String> values = Map.of(
                "current_page", String.valueOf(page),
                "total_pages", String.valueOf(pages),
                "page", String.valueOf(page),
                "pages", String.valueOf(pages));
        drawPlaced(list.previous(), values, Pages.hasPrevious(page));
        drawPlaced(list.next(), values, Pages.hasNext(page, rows, list.perPage()));
    }

    /**
     * Draws a page button, or puts back what the slot would otherwise hold.
     *
     * <p>Restored rather than emptied, because the page a button disappears on
     * changes while the menu is open: leaving a hole would make the background
     * flicker on and off as somebody pages through a list.
     */
    private void drawPlaced(UiSection.Placed placed, Map<String, String> values,
                            boolean reachable) {
        if (placed == null) {
            return;
        }
        if (!reachable) {
            drawBackground(placed.slot());
            return;
        }
        paint(placed.slot(), placed.item(), values, Set.of(), Set.of());
        slots.put(placed.slot(), Rendered.of(placed.item()));
    }

    /**
     * Draws what a slot holds when the button that owns it is not drawn.
     *
     * <p>The same order the menu drew it in to begin with, so a slot a button
     * vacated looks exactly like it would have if the button had never been
     * declared there.
     */
    private void drawBackground(int slot) {
        UiItem beneath = definition.beneath(slot);
        if (beneath == null) {
            put(slot, null);
            slots.remove(slot);
            return;
        }
        if (beneath == definition.items().get(slot)) {
            // A menu that puts a button under a page arrow gets its button
            // back, condition and clicks included, rather than glass over it.
            drawFixed(slot, beneath);
            return;
        }
        paint(slot, beneath);
        slots.put(slot, Rendered.FILLER);
    }

    /** Whether a slot's condition lets it be shown. */
    private boolean passes(UiItem item, Map<String, String> values) {
        String condition = item.condition();
        if (condition == null) {
            return true;
        }
        return Conditions.test(condition, side -> resolve(side, values));
    }

    /**
     * Resolves a string for this viewer, with row values and the menu context.
     *
     * <p>The values go in as literal text after the parse, never into the
     * string before it: a row value is often something a player typed, and
     * pasted in first it could carry tags or placeholders of its own.
     */
    private String resolve(String text, Map<String, String> values) {
        Map<String, String> context = contextText();
        Map<String, String> all = new HashMap<>(context.size() + values.size());
        all.putAll(context);
        // The row's own values win over the menu's, as they always have.
        all.putAll(values);
        return Text.of(text).withAll(all, Set.of(), Set.of()).forPlayer(viewer).verbatim().plain();
    }

    /** The row's own verbatim values, plus the context keys the menu named. */
    private Set<String> verbatim(Set<String> row) {
        if (verbatimContext.isEmpty()) {
            return row;
        }
        if (row.isEmpty()) {
            return verbatimContext;
        }
        Set<String> all = new LinkedHashSet<>(verbatimContext);
        all.addAll(row);
        return all;
    }

    /**
     * Row values on top of the menu's context.
     *
     * <p>The row wins: a leaderboard's context names the kit, and each row names
     * its own player.
     */
    static Map<String, String> merged(Map<String, ?> context, Map<String, String> values) {
        Map<String, String> all = new LinkedHashMap<>();
        for (Map.Entry<String, ?> value : context.entrySet()) {
            all.put(value.getKey(), text(value.getValue()));
        }
        all.putAll(values);
        return all;
    }

    /**
     * A context value as text, read first when it is live.
     *
     * <p>Nothing is an empty value, as it is in a row and in a title.
     */
    static String text(Object value) {
        Object read = value instanceof Supplier<?> reader ? reader.get() : value;
        return read == null ? "" : String.valueOf(read);
    }

    /**
     * Which of them are parsed rather than inserted as text.
     *
     * <p>Package-private so the decision can be exercised without a server: an
     * {@code ItemStack} needs the registry, and this is the whole of what
     * changed.
     *
     * <p>A row naming the same key as the context keeps whichever the caller
     * chose for it, because at that point it is the row's value being drawn.
     */
    static Set<String> parsed(Map<String, ?> context, Map<String, String> values,
                              Set<String> formatted) {
        // A fixed slot carries no row values, and most rows ask for no formatting.
        // The answer is then exactly the context's keys, so the copy would be a
        // per-slot allocation of a set that already says the right thing. The view
        // is only ever read — the renderer asks it `contains` and nothing else — and
        // is not retained past the render it was handed to.
        if (values.isEmpty() && formatted.isEmpty()) {
            return context.keySet();
        }
        Set<String> parsed = new LinkedHashSet<>(context.keySet());
        parsed.removeAll(values.keySet());
        parsed.addAll(formatted);
        return parsed;
    }

    /**
     * Sends the title again, when what it says has changed.
     *
     * <p>Only when it names the page, and only when the text actually moved:
     * retitling costs a packet and makes the client re-request the window's
     * contents, which is far too much for a title that reads the same.
     *
     * <p>Silently does nothing without PacketEvents. A title stuck on the page
     * it opened at is what every menu did before this existed, and is not worth
     * refusing to page for.
     */
    private void retitle() {
        String written = definition.title();
        if (written.indexOf('%') < 0 || !namesAPage(written)) {
            return;
        }

        UiSection only = definition.section();
        int page = only == null ? 1 : page(only.id());
        int pages = only == null ? 1 : only.pagesFor(entries(only.id()).size());

        String filled = filledTitle(written, context, page, pages);
        if (filled.equals(lastTitle)) {
            return;
        }
        lastTitle = filled;
        Titles.retitle(viewer, definition.size(), titleText(written, context, page, pages).forPlayer(viewer).build());
    }

    /** Returns whether a title asks for a page number at all. */
    private static boolean namesAPage(String written) {
        return written.contains("%current_page%") || written.contains("%total_pages%")
                || written.contains("%page%") || written.contains("%pages%");
    }

    /**
     * The title, with its placeholders resolved for this viewer.
     *
     * <p>Page numbers are supplied by the menu itself, because a title reading
     * {@code %current_page%/%total_pages%} is how nearly every paginated menu
     * in the ecosystem is written and no plugin should have to answer a
     * question the menu already knows the answer to.
     *
     * <p>A window being opened is on its first page and has no rows yet, so
     * both read one. What they say afterwards is {@link #retitle()}'s job.
     */
    static Component title(UiDefinition definition, Player viewer, Map<String, Object> context) {
        return title(definition, viewer, context, Map.of());
    }

    /**
     * The same title, for a window whose rows are already known.
     *
     * <p>Counted from the rows it is about to be seeded with, so a paginated
     * menu opens saying {@code 1/5} instead of opening on {@code 1/1} and
     * spending a retitle packet to correct itself before anybody read it.
     */
    static Component title(UiDefinition definition, Player viewer, Map<String, Object> context,
                           Map<String, ? extends Collection<UiEntry>> sections) {
        UiSection only = definition.section();
        Collection<UiEntry> rows = only == null ? null : sections.get(only.id());
        int pages = rows == null ? 1 : only.pagesFor(rows.size());
        return titleText(definition.title(), context, 1, pages).forPlayer(viewer).build();
    }

    /**
     * The title ready to build: page numbers written in, context values
     * substituted after the parse with only their colours honoured.
     *
     * <p>A context value is often a name a player chose. Pasted into the
     * string before the parse it could carry a click, a hover, or a
     * placeholder of its own into the window title.
     */
    static Text titleText(String written, Map<String, Object> context, int page, int pages) {
        Text title = Text.of(paged(written, page, pages));
        for (Map.Entry<String, Object> value : context.entrySet()) {
            title = title.withColored('%' + value.getKey() + '%', text(value.getValue()));
        }
        return title;
    }

    /** The title with its page numbers written in, which are only ever digits. */
    private static String paged(String written, int page, int pages) {
        return written.replace("%current_page%", String.valueOf(page))
                .replace("%page%", String.valueOf(page))
                .replace("%total_pages%", String.valueOf(pages))
                .replace("%pages%", String.valueOf(pages));
    }

    /**
     * A title with its values in, as one string.
     *
     * <p>Only compared, to tell whether a retitle has anything new to say;
     * what is drawn is built by {@link #titleText}, which never parses the
     * values. Package-private so it can be exercised without a server, which is
     * exactly where the bug was: nothing filled the page numbers in, so the
     * player read the placeholder names off the top of the window.
     *
     * @param written the title as the file wrote it
     * @param context what the menu is about
     * @param page    the page being shown
     * @param pages   how many there are
     */
    static String filledTitle(String written, Map<String, Object> context, int page, int pages) {
        // The page numbers go in first, because the list is the authority on
        // which page it is showing. A context value of the same name would
        // otherwise outlive the click that moved it.
        String text = paged(written, page, pages);
        for (Map.Entry<String, Object> value : context.entrySet()) {
            text = text.replace('%' + value.getKey() + '%', text(value.getValue()));
        }
        return text;
    }

    /**
     * Builds the window itself.
     *
     * <p>The title is a component rather than a legacy string: a legacy string
     * cannot carry the shadow the server draws under every line, so a window
     * created from one opened unshadowed and only got its shadow on the first
     * retitle.
     *
     * <p>The holder is how a click finds its way back here. Tracking open
     * menus by player instead would answer the wrong question the moment
     * somebody opens a chest while a menu is on screen.
     */
    static Inventory inventoryFor(MenuHolder holder, UiDefinition definition, Player viewer,
                                  Map<String, Object> context) {
        return inventoryFor(holder, definition, viewer, context, Map.of());
    }

    /** The same window, titled for the rows it is about to be seeded with. */
    static Inventory inventoryFor(MenuHolder holder, UiDefinition definition, Player viewer,
                                  Map<String, Object> context,
                                  Map<String, ? extends Collection<UiEntry>> sections) {
        Component title = title(definition, viewer, context, sections);
        org.bukkit.event.inventory.InventoryType type = definition.kind().type();
        // A chest is created by slot count because its size is configured;
        // everything else has a fixed shape the server already knows.
        return type == null
                ? Bukkit.createInventory(holder, definition.size(), title)
                : Bukkit.createInventory(holder, type, title);
    }

}
