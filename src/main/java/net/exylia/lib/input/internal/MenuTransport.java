package net.exylia.lib.input.internal;

import net.exylia.lib.input.ChoiceInput;
import net.exylia.lib.input.ConfirmInput;
import net.exylia.lib.input.FlagInput;
import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputParser;
import net.exylia.lib.input.InputRequest;
import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small inventory transport for choices and boolean requests.
 *
 * <p>This intentionally uses a private holder instead of the configuration UI
 * module. {@code UiDefinition} models reusable, plugin-owned YAML menus and
 * action bindings; an input window is ephemeral, carries typed values, and must
 * complete one {@link InputSession}. Adapting it into commands or string action
 * data would discard that type safety and add a second lifecycle to coordinate.
 *
 * <p>Every click and drag in the complete view is cancelled before dispatch.
 * The client therefore cannot move its own items through shift-click, hotbar
 * keys, offhand swap, double-click collection, drop, or creative middle-click,
 * even when a future Bukkit click type is not explicitly understood.
 *
 * @since 1.31.0
 */
public final class MenuTransport implements Transport {

    private static final int PAGE_CAPACITY = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int CANCEL_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final Set<UUID> programmaticCloses = ConcurrentHashMap.newKeySet();

    /** Creates the transport used by ExyliaLib. */
    public MenuTransport(@NotNull Plugin plugin) {
        java.util.Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public boolean show(@NotNull InputSession session) {
        Player player = Bukkit.getPlayer(session.playerId());
        if (player == null || !player.isOnline()) {
            return false;
        }

        List<Option> options = options(session.request());
        if (options == null) {
            return false;
        }
        open(session, player, options, 0, false);
        return true;
    }

    /**
     * Routes a click after {@link InputListener} identifies this holder.
     * Cancellation happens unconditionally and first: unsupported and malicious
     * click modes fail closed instead of falling through to Bukkit item movement.
     */
    void click(@NotNull InventoryClickEvent event) {
        event.setCancelled(true);
        MenuHolder holder = holderOf(event.getView().getTopInventory());
        if (holder == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InputSession session = holder.session();
        if (InputRuntime.active(player.getUniqueId()) != session
                || session.transportKind() != TransportKind.MENU) {
            return;
        }

        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.getInventory().getSize()) {
            return;
        }
        int cancelSlot = holder.getInventory().getSize() == 54
                ? CANCEL_SLOT : holder.getInventory().getSize() - 1;
        if (slot == cancelSlot) {
            session.end(InputOutcome.CANCELLED);
            return;
        }
        if (slot == PREVIOUS_SLOT && holder.page() > 0) {
            open(session, player, holder.options(), holder.page() - 1, true);
            return;
        }
        if (slot == NEXT_SLOT && holder.page() + 1 < pages(holder.options().size())) {
            open(session, player, holder.options(), holder.page() + 1, true);
            return;
        }

        int optionIndex = holder.page() * PAGE_CAPACITY + slot;
        if (slot >= PAGE_CAPACITY || optionIndex >= holder.options().size()) {
            return;
        }
        Option option = holder.options().get(optionIndex);
        complete(session, player, option.raw());
    }

    /** Cancels every drag in the input view, including drags confined to player slots. */
    void drag(@NotNull InventoryDragEvent event) {
        event.setCancelled(true);
    }

    /**
     * Treats a genuine window close as cancellation.
     *
     * <p>Opening another page and terminal cleanup also close an inventory. The
     * session id is inserted into {@link #programmaticCloses} before Bukkit is
     * asked to replace or close the window; the synchronous close event consumes
     * that marker. Without this ordering, paging or choosing an option would
     * cancel its own session before the intended action could complete.
     */
    void closed(@NotNull InventoryCloseEvent event) {
        MenuHolder holder = holderOf(event.getInventory());
        if (holder == null) {
            return;
        }
        InputSession session = holder.session();
        if (programmaticCloses.remove(session.id())) {
            return;
        }
        if (InputRuntime.active(session.playerId()) == session
                && session.transportKind() == TransportKind.MENU) {
            session.end(InputOutcome.CANCELLED);
        }
    }

    @Override
    public void close(@NotNull InputSession session) {
        Player player = Bukkit.getPlayer(session.playerId());
        if (player == null || !player.isOnline()) {
            programmaticCloses.remove(session.id());
            return;
        }
        MenuHolder holder = holderOf(player.getOpenInventory().getTopInventory());
        if (holder == null || holder.session() != session) {
            programmaticCloses.remove(session.id());
            return;
        }
        programmaticCloses.add(session.id());
        player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
        programmaticCloses.remove(session.id());
    }

    @Override
    public @NotNull TransportKind kind() {
        return TransportKind.MENU;
    }

    private void open(InputSession session, Player player, List<Option> options,
                      int page, boolean replacing) {
        if (replacing) {
            programmaticCloses.add(session.id());
        }
        try {
            int size = inventorySize(options.size());
            MenuHolder holder = new MenuHolder(session, options, page);
            Component title = titleOf(session.request(), page, pages(options.size()));
            Inventory inventory = Bukkit.createInventory(holder, size, title);
            holder.bind(inventory);
            draw(holder);
            player.openInventory(inventory);
        } finally {
            if (replacing) {
                programmaticCloses.remove(session.id());
            }
        }
    }

    private static void draw(MenuHolder holder) {
        Inventory inventory = holder.getInventory();
        int start = holder.page() * PAGE_CAPACITY;
        int end = Math.min(start + PAGE_CAPACITY, holder.options().size());
        for (int index = start; index < end; index++) {
            Option option = holder.options().get(index);
            inventory.setItem(index - start, item(option.icon(), option.label()));
        }

        if (inventory.getSize() == 54 && holder.page() > 0) {
            inventory.setItem(PREVIOUS_SLOT, item(Material.ARROW, "{primary}Previous"));
        }
        int cancelSlot = inventory.getSize() == 54 ? CANCEL_SLOT : inventory.getSize() - 1;
        inventory.setItem(cancelSlot, item(Material.BARRIER, "{error}Cancel"));
        if (inventory.getSize() == 54
                && holder.page() + 1 < pages(holder.options().size())) {
            inventory.setItem(NEXT_SLOT, item(Material.ARROW, "{primary}Next"));
        }
    }

    private static void complete(InputSession session, Player player, String raw) {
        if (!(session.request() instanceof InputRequest<?, ?> request)) {
            return;
        }
        InputParser.Parsed<?> parsed = request.parseRaw(raw);
        if (parsed.ok()) {
            session.complete(parsed.value());
            return;
        }
        Text.of("{error}%error%")
                .with("%error%", parsed.error() == null
                        ? "That option is not accepted." : parsed.error())
                .send(player);
    }

    private static @Nullable List<Option> options(Object request) {
        if (request instanceof ChoiceInput<?> choice) {
            return choiceOptions(choice);
        }
        if (request instanceof ConfirmInput confirm) {
            Material yes = confirm.isDangerous() ? Material.RED_CONCRETE : Material.LIME_CONCRETE;
            return List.of(
                    new Option("true", confirm.confirmLabel(), yes),
                    new Option("false", confirm.denyLabel(), Material.GRAY_CONCRETE));
        }
        if (request instanceof FlagInput) {
            return List.of(
                    new Option("true", "Yes", Material.LIME_CONCRETE),
                    new Option("false", "No", Material.RED_CONCRETE));
        }
        return null;
    }

    private static <T> List<Option> choiceOptions(ChoiceInput<T> choice) {
        List<Option> options = new ArrayList<>(choice.choices().size());
        for (T value : choice.choices()) {
            options.add(new Option(choice.keyOf(value), choice.labelOf(value), choice.iconOf(value)));
        }
        return List.copyOf(options);
    }

    private static int inventorySize(int optionCount) {
        if (optionCount > PAGE_CAPACITY) {
            return 54;
        }
        int optionRows = Math.max(1, (optionCount + 8) / 9);
        return Math.min(54, (optionRows + 1) * 9);
    }

    private static int pages(int optionCount) {
        return Math.max(1, (optionCount + PAGE_CAPACITY - 1) / PAGE_CAPACITY);
    }

    private static Component titleOf(Object request, int page, int pages) {
        String prompt = request instanceof InputRequest<?, ?> input
                ? input.prompt() : "Choose an option";
        if (pages == 1) {
            return Text.of("%prompt%").withFormatted("%prompt%", prompt).build();
        }
        return Text.of("%prompt% {muted}(%page%/%pages%)")
                // The plugin's own text, in the plugin's own notation.
                .withFormatted("%prompt%", prompt)
                .with("%page%", page + 1)
                .with("%pages%", pages)
                .build();
    }

    private static ItemStack item(Material material, String label) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.of(label).build());
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Finds the transport from server-owned holder state rather than the active
     * registry. This lets the listener keep cancelling a stale window during
     * the short interval between terminal arbitration and scheduled cleanup.
     */
    static @Nullable MenuTransport transportOf(@NotNull Inventory inventory) {
        MenuHolder holder = holderOf(inventory);
        if (holder == null) {
            return null;
        }
        Transport transport = holder.session().transport();
        return transport instanceof MenuTransport menu ? menu : null;
    }

    private static @Nullable MenuHolder holderOf(Inventory inventory) {
        return inventory.getHolder(false) instanceof MenuHolder holder ? holder : null;
    }

    private record Option(String raw, String label, Material icon) {
    }

    /** Holder identity is the authority; client-reported item stacks are ignored. */
    private static final class MenuHolder implements InventoryHolder {
        private final InputSession session;
        private final List<Option> options;
        private final int page;
        private Inventory inventory;

        private MenuHolder(InputSession session, List<Option> options, int page) {
            this.session = session;
            this.options = options;
            this.page = page;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        private InputSession session() {
            return session;
        }

        private List<Option> options() {
            return options;
        }

        private int page() {
            return page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
