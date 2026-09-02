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

    /** Values the menu is about, also filled into everything it draws. */
    private final Map<String, Object> context = new LinkedHashMap<>();

    /** What to stop when the menu closes. */
    private final List<ActionExecution> pending = new ArrayList<>();

    /** The title last sent, so an unchanged one costs no packet. */
    /** Built on first use; the definition it derives from cannot change. */
    private Set<Integer> inputSlots;

    private String lastTitle;

    private boolean open = true;

    /** The running open animation, if one is still revealing slots. */
    private net.exylia.lib.task.TaskHandle animation;

    /** Which frame of that animation comes next. */
    private int frame;

    /** Slots the animation has taken away and not yet put back. */
    private Map<Integer, ItemStack> hidden = new LinkedHashMap<>();

    /** The redraw timer, when the menu asked for one. */
    private net.exylia.lib.task.TaskHandle refresher;

    Session(MenuRuntime runtime, Player viewer, UiDefinition definition, PluginItems items,
            Inventory inventory, int generation, Map<String, Object> context) {
        this.runtime = runtime;
        this.viewer = viewer;
        this.definition = definition;
        this.items = items;
        this.inventory = inventory;
        this.generation = generation;
        this.context.putAll(context);
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
        drawSection(list);
        retitle();
        runtime.play(viewer, definition.sounds().page());
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
        entries.put(section, List.copyOf(rows));
        // Keep the reader where they were, as far as there is still a page
        // there. A leaderboard refreshing under somebody on page three leaves
        // them on page three.
        pages.put(section, Pages.clamp(page(section), rows.size(), list.perPage()));
        drawSection(list);
        // The count is part of the title, and it just changed: a menu filled
        // after it opened would otherwise say "1/1" over five pages of rows.
        retitle();
    }

    @Override
    public void entries(@NotNull Collection<UiEntry> rows) {
        UiSection only = definition.section();
        if (only != null) {
            entries(only.id(), rows);
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
        int redrawn = 0;
        for (Map.Entry<Integer, UiItem> fixed : definition.items().entrySet()) {
            if (dependsOnAny(fixed.getValue(), changed)) {
                drawFixed(fixed.getKey(), fixed.getValue());
                redrawn++;
            }
        }
        for (UiSection list : definition.sections().values()) {
            UiItem template = list.template(null);
            if (template != null && dependsOnAny(template, changed)) {
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
        return false;
    }

    @Override
    public boolean invalidateSlot(int slot) {
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

    /**
     * Redraws what a timed refresh should redraw.
     *
     * <p>{@code FULL} redraws everything. {@code SMART} redraws only the slots
     * that can actually differ from what is already on screen — a menu of
     * decorations on a twenty-tick timer should cost nothing, and redrawing a
     * static slot every second is packets for an identical item.
     */
    private void tickRefresh() {
        if (!isOpen()) {
            return;
        }
        if (definition.refresh().mode() == UiRefresh.Mode.FULL) {
            draw();
            return;
        }
        for (Map.Entry<Integer, UiItem> fixed : definition.items().entrySet()) {
            if (fixed.getValue().isDynamic()) {
                drawFixed(fixed.getKey(), fixed.getValue());
            }
        }
        for (UiSection list : definition.sections().values()) {
            UiItem template = list.template(null);
            if ((template != null && template.isDynamic()) || !entries(list.id()).isEmpty()) {
                drawSection(list);
            }
        }
    }

    /**
     * Starts the redraw timer, if the menu asked for one.
     *
     * <p>An entity timer, so it dies with the player. Started only when there
     * is something that could change: a static menu on a {@code SMART} timer
     * would wake up every second to decide it had nothing to do.
     */
    void startRefreshing() {
        if (!definition.refresh().isTimed() || !definition.isDynamic()) {
            return;
        }
        refresher = runtime.tick(viewer, definition.refresh().interval(), handle -> {
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
        if (!definition.refresh().isOnClick()) {
            return;
        }
        int delay = definition.refresh().clickDelay();
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
        for (Map.Entry<Integer, UiItem> fixed : definition.items().entrySet()) {
            if (fixed.getValue().isDynamic() || fixed.getKey() == clicked) {
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
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    @Override
    public @NotNull UiSession context(@NotNull String key, @NotNull Object value) {
        context.put(key, value);
        return this;
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

    /** The runtime that owns this menu. */
    MenuRuntime runtime() {
        return runtime;
    }

    /**
     * Shows the rest of the menu at once.
     *
     * <p>Called when a player clicks: somebody who is already interacting has
     * stopped watching the animation, and making them wait for a button to
     * finish appearing is the complaint every animated menu earns.
     */
    void skipAnimation() {
        if (animation != null) {
            finishAnimation();
        }
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

    /** Stops everything this menu started. Called once, when it closes. */
    void released() {
        open = false;
        finishAnimation();
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
        inventory.clear();
        drawFillers();
        for (Map.Entry<Integer, UiItem> fixed : definition.items().entrySet()) {
            drawFixed(fixed.getKey(), fixed.getValue());
        }
        for (UiSection list : definition.sections().values()) {
            drawSection(list);
        }
    }

    /**
     * Reveals the menu a frame at a time.
     *
     * <p>Everything is already drawn and recorded by the time this starts, so a
     * click landing mid-animation on a slot that has not appeared yet still
     * does the right thing: the session knows what is there even while the
     * client cannot see it. Drawing it after would make the animation a window
     * during which buttons silently do nothing.
     *
     * @param frames what appears when
     * @param speed  ticks between frames
     */
    void animate(List<List<Integer>> frames, int speed) {
        // Snapshot what is on screen, then take it away and put it back in
        // pieces. Copied because the inventory is about to be cleared.
        Map<Integer, ItemStack> drawn = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null) {
                drawn.put(slot, item);
            }
        }
        inventory.clear();

        hidden = drawn;
        frame = 0;
        animation = runtime.tick(viewer, speed, handle -> {
            if (!isOpen() || frame >= frames.size()) {
                // They closed it, opened something else, or it finished.
                finishAnimation();
                return;
            }
            for (int slot : frames.get(frame)) {
                reveal(slot);
            }
            frame++;
        });
    }

    /**
     * Stops a running animation and shows whatever it had not reached.
     *
     * <p>The important half. Cancelling the timer alone would leave the
     * unrevealed slots empty for as long as the menu stayed open — a menu that
     * is interrupted mid-animation would be permanently missing its corners.
     */
    private void finishAnimation() {
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
        if (hidden.isEmpty()) {
            return;
        }
        for (int slot : List.copyOf(hidden.keySet())) {
            reveal(slot);
        }
    }

    /** Puts one slot back on screen, if the animation was still holding it. */
    private void reveal(int slot) {
        ItemStack item = hidden.remove(slot);
        if (item != null) {
            inventory.setItem(slot, item);
        }
    }

    /**
     * Writes one slot, respecting an animation that has not reached it.
     *
     * <p>Every drawing path goes through here. A list refreshed while the menu
     * is still appearing would otherwise write into a slot the animation is
     * about to overwrite with what was there before — the new rows would flash
     * and vanish. Held instead, and revealed with the right contents.
     */
    private void put(int slot, ItemStack item) {
        if (isAnimating() && hidden.containsKey(slot)) {
            if (item == null) {
                // Emptied rather than replaced. Dropping it from the pending
                // set is what makes it stay empty: a null left in there would
                // be skipped on reveal and the old item would come back.
                hidden.remove(slot);
                inventory.setItem(slot, null);
                return;
            }
            hidden.put(slot, item);
            return;
        }
        inventory.setItem(slot, item);
    }

    /**
     * Returns whether an animation is still hiding part of the menu.
     *
     * <p>Anything that redraws asks first, because writing into a slot the
     * animation still holds would be undone the moment it got there.
     */
    private boolean isAnimating() {
        return animation != null;
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
        }

        // Named panels first, so the background does not paint over them, and
        // in file order so the first to claim a slot keeps it.
        for (UiFillers.Panel panel : fillers.custom()) {
            ItemStack drawn = render(panel.item(), Map.of());
            for (int slot : panel.slots()) {
                if (slot < 0 || slot >= definition.size() || reserved.contains(slot)) {
                    continue;
                }
                put(slot, drawn);
                slots.put(slot, Rendered.FILLER);
                reserved.add(slot);
            }
        }

        if (fillers.global() == null) {
            return;
        }
        ItemStack background = render(fillers.global(), Map.of());
        for (int slot = 0; slot < definition.size(); slot++) {
            if (reserved.contains(slot)) {
                continue;
            }
            put(slot, background);
            slots.put(slot, Rendered.FILLER);
        }
    }

    /**
     * Draws one fixed slot.
     *
     * <p>A slot whose condition fails is not merely blank: it is not there.
     * Nothing is recorded for it, so a click on it finds nothing and does
     * nothing, which is the same answer as clicking the background.
     */
    private void drawFixed(int slot, UiItem item) {
        if (!passes(item, Map.of())) {
            put(slot, null);
            slots.remove(slot);
            return;
        }
        put(slot, render(item, Map.of()));
        slots.put(slot, Rendered.of(item));
    }

    /** Draws one list at its current page. */
    private void drawSection(UiSection list) {
        List<UiEntry> rows = entries.getOrDefault(list.id(), List.of());
        int page = Pages.clamp(page(list.id()), rows.size(), list.perPage());
        pages.put(list.id(), page);

        int first = Pages.indexOf(page, list.perPage(), 0);
        List<Integer> where = list.slots();
        for (int index = 0; index < where.size(); index++) {
            int slot = where.get(index);
            int entryIndex = first + index;
            if (entryIndex >= rows.size()) {
                drawSectionFiller(list, slot);
                continue;
            }
            UiEntry entry = rows.get(entryIndex);
            UiItem template = list.template(entry.template());

            // A row that brought its own item. There is no template to render
            // and none to take a condition or click bindings from, so the item
            // is drawn as given and the row's value is what a click reads.
            if (entry.hasItem()) {
                put(slot, entry.item());
                slots.put(slot, Rendered.of(template, entry, list.id()));
                continue;
            }
            if (template == null || !passes(template, entry.values())) {
                drawSectionFiller(list, slot);
                continue;
            }
            put(slot, render(template, entry.values(), entry.formatted()));
            slots.put(slot, Rendered.of(template, entry, list.id()));
        }
        drawNavigation(list, rows.size());
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
        put(slot, render(filler, Map.of()));
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
        put(placed.slot(), render(placed.item(), values));
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
        put(slot, render(beneath, Map.of()));
        slots.put(slot, Rendered.FILLER);
    }

    /** Whether a slot's condition lets it be shown. */
    private boolean passes(UiItem item, Map<String, String> values) {
        String condition = item.condition();
        if (condition == null) {
            return true;
        }
        return Conditions.test(resolve(condition, values));
    }

    /** Resolves a string for this viewer, with row values and the menu context. */
    private String resolve(String text, Map<String, String> values) {
        String filled = text;
        for (Map.Entry<String, String> value : values.entrySet()) {
            filled = filled.replace('%' + value.getKey() + '%', value.getValue());
        }
        for (Map.Entry<String, Object> value : context.entrySet()) {
            filled = filled.replace('%' + value.getKey() + '%', String.valueOf(value.getValue()));
        }
        return Text.of(filled).forPlayer(viewer).plain();
    }

    /** Builds a slot's item, with the row's values and the menu's context. */
    private ItemStack render(UiItem item, Map<String, String> values) {
        return render(item, values, Set.of());
    }

    /**
     * Builds a slot's item, honouring the row values that carry formatting.
     *
     * <p>Context values are parsed; row values are literal unless the caller
     * asked otherwise. The two are not the same kind of thing. A row value is
     * one entry in a list, and lists are full of names players chose, so
     * inserting them as text is what stops somebody called {@code <rainbow>}
     * from repainting the menu. A context value describes the whole screen and
     * is written by whoever wrote the menu — the same person who wrote the
     * template it lands in, and in the same file.
     *
     * <p>Everything else already agreed: the title and slot conditions
     * substitute into the string before parsing, so a colour in a context value
     * worked there. Only the items disagreed, which meant a menu whose title
     * came out right had buttons spelling {@code {success}&l} at the player.
     */
    private ItemStack render(UiItem item, Map<String, String> values, Set<String> formatted) {
        if (context.isEmpty()) {
            return items.renderIcon(item.item(), viewer, values, formatted);
        }
        return items.renderIcon(item.item(), viewer,
                merged(context, values), parsed(context, values, formatted));
    }

    /**
     * Row values on top of the menu's context.
     *
     * <p>The row wins: a leaderboard's context names the kit, and each row names
     * its own player.
     */
    static Map<String, String> merged(Map<String, Object> context, Map<String, String> values) {
        Map<String, String> all = new LinkedHashMap<>();
        for (Map.Entry<String, Object> value : context.entrySet()) {
            all.put(value.getKey(), String.valueOf(value.getValue()));
        }
        all.putAll(values);
        return all;
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
    static Set<String> parsed(Map<String, Object> context, Map<String, String> values,
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
        Titles.retitle(viewer, definition.size(), Text.of(filled).forPlayer(viewer).build());
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
    static String title(UiDefinition definition, Player viewer, Map<String, Object> context) {
        return title(definition, viewer, context, Map.of());
    }

    /**
     * The same title, for a window whose rows are already known.
     *
     * <p>Counted from the rows it is about to be seeded with, so a paginated
     * menu opens saying {@code 1/5} instead of opening on {@code 1/1} and
     * spending a retitle packet to correct itself before anybody read it.
     */
    static String title(UiDefinition definition, Player viewer, Map<String, Object> context,
                        Map<String, ? extends Collection<UiEntry>> sections) {
        UiSection only = definition.section();
        Collection<UiEntry> rows = only == null ? null : sections.get(only.id());
        int pages = rows == null ? 1 : only.pagesFor(rows.size());
        return Text.of(filledTitle(definition.title(), context, 1, pages))
                .forPlayer(viewer).legacy();
    }

    /**
     * A title with its values in, before it is parsed.
     *
     * <p>Package-private so it can be exercised without a server, which is
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
        String text = written.replace("%current_page%", String.valueOf(page))
                .replace("%page%", String.valueOf(page))
                .replace("%total_pages%", String.valueOf(pages))
                .replace("%pages%", String.valueOf(pages));
        for (Map.Entry<String, Object> value : context.entrySet()) {
            text = text.replace('%' + value.getKey() + '%', String.valueOf(value.getValue()));
        }
        return text;
    }

    /**
     * Builds the window itself.
     *
     * <p>The title is a legacy string rather than a component on purpose: the
     * component-taking overload of {@code createInventory} is Paper's, and the
     * library has to load on Spigot. A title carries colour and nothing else,
     * so nothing is lost.
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
        String title = title(definition, viewer, context, sections);
        org.bukkit.event.inventory.InventoryType type = definition.kind().type();
        // A chest is created by slot count because its size is configured;
        // everything else has a fixed shape the server already knows.
        return type == null
                ? Bukkit.createInventory(holder, definition.size(), title)
                : Bukkit.createInventory(holder, type, title);
    }

}
