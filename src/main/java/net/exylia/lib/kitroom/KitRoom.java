package net.exylia.lib.kitroom;

import net.exylia.lib.database.Databases;
import net.exylia.lib.database.Repository;
import net.exylia.lib.debug.Debug;
import net.exylia.lib.kitroom.internal.CategoryDescriptor;
import net.exylia.lib.kitroom.internal.KitRoomDefaults;
import net.exylia.lib.kitroom.internal.KitRoomMenu;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.util.editor.Editors;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One plugin's kit room: categories of items players take copies of to build
 * their own kits.
 *
 * <p>Staff fill it with {@link #edit}; players browse it with {@link #open},
 * where every click hands a copy of the item into their inventory. A plugin
 * that lets players save what they built checks it with {@link #allows} before
 * storing it, so a kit can only ever hold what the room offered.
 *
 * <pre>{@code
 * KitRoom room = KitRooms.of(plugin);
 * room.open(player, kit.categories());                 // build
 * if (!room.allows(player.getInventory().getContents(), kit.categories())) refuse();
 * }</pre>
 *
 * <p>Kept in the owning plugin's database (table {@code exylia_kitroom}) and
 * held in memory; an empty room is filled with {@code KitRoomDefaults} once.
 *
 * @since 1.511.0
 */
public final class KitRoom {

    private final Plugin plugin;
    private final Repository<KitRoomCategory> repository;
    private final Map<String, KitRoomCategory> categories = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> loaded;

    KitRoom(@NotNull Plugin plugin, @NotNull Supplier<? extends CompletionStage<List<KitRoomCategory>>> seed) {
        this.plugin = plugin;
        this.repository = Databases.of(plugin).repository(KitRoomCategory.class);
        this.loaded = repository.findAll().thenCompose(rows -> {
            rows.forEach(row -> categories.put(row.id(), row));
            if (!rows.isEmpty()) return CompletableFuture.completedFuture(null);
            // ponytail: seeded whenever the table is empty, so a room staff
            // emptied on purpose comes back on restart; add a flag if anyone wants that.
            return seed.get().toCompletableFuture().thenCompose(seeded -> {
                List<KitRoomCategory> first = seeded.isEmpty() ? KitRoomDefaults.build() : seeded;
                first.forEach(row -> categories.put(row.id(), row));
                return repository.saveAll(first);
            });
        }).exceptionally(failure -> {
            Debug.of(plugin).error("The kit room could not be loaded.", failure);
            return null;
        });
    }

    /** The plugin this room belongs to. */
    public @NotNull Plugin plugin() {
        return plugin;
    }

    /** Completes once the room is read from the database. */
    public @NotNull CompletableFuture<Void> loaded() {
        return loaded;
    }

    /** Every category, in tab order. */
    public @NotNull List<KitRoomCategory> categories() {
        return categories.values().stream()
                .sorted(Comparator.comparingInt(KitRoomCategory::priority).thenComparing(KitRoomCategory::id))
                .toList();
    }

    /**
     * The categories among {@code ids}, in tab order; every category when
     * {@code ids} is empty. Unknown ids are skipped.
     */
    public @NotNull List<KitRoomCategory> categories(@NotNull Collection<String> ids) {
        if (ids.isEmpty()) return categories();
        return categories().stream().filter(category -> ids.contains(category.id())).toList();
    }

    public @NotNull Optional<KitRoomCategory> category(@NotNull String id) {
        return Optional.ofNullable(categories.get(id));
    }

    /**
     * Whether every item is one the given categories offer (every category when
     * {@code ids} is empty). Empty slots pass; amounts are not checked, since
     * players take as many copies as they like.
     */
    public boolean allows(ItemStack @NotNull [] items, @NotNull Collection<String> ids) {
        List<KitRoomCategory> offered = categories(ids);
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            if (offered.stream().noneMatch(category -> category.holds(item))) return false;
        }
        return true;
    }

    /**
     * Opens the room for a player to take items from.
     *
     * @param viewer     who browses; any thread
     * @param categories which categories to show, every one when empty
     */
    public void open(@NotNull Player viewer, @NotNull Collection<String> categories) {
        open(viewer, categories, "");
    }

    /**
     * Opens the room on one tab.
     *
     * @param viewer     who browses; any thread
     * @param categories which categories to show, every one when empty
     * @param first      the tab shown first; the first one when it is not among them
     * @since 1.512.0
     */
    public void open(@NotNull Player viewer, @NotNull Collection<String> categories, @NotNull String first) {
        KitRoomMenu.open(this, viewer, List.copyOf(categories), first);
    }

    /**
     * Opens the staff editor: add, rename, reorder, fill and delete categories.
     *
     * @param viewer who edits; any thread
     * @param back   what to open when the editor closes, saved or not
     */
    public void edit(@NotNull Player viewer, @NotNull Runnable back) {
        Editors.of(plugin).list(new CategoryDescriptor(plugin), KitRoomCategory.class, categories())
                .title(Phrases.tr("{primary}&lKIT ROOM {letters_black}» {highlight}Categories"))
                .onSave(edited -> {
                    save(edited);
                    back.run();
                })
                .onCancel(back)
                .open(viewer);
    }

    private void save(List<KitRoomCategory> edited) {
        List<KitRoomCategory> rows = new ArrayList<>(edited.size());
        for (int index = 0; index < edited.size(); index++) {
            KitRoomCategory category = edited.get(index);
            rows.add(category.with(category.name(), index, category.itemList()));
        }
        Set<String> kept = new HashSet<>();
        rows.forEach(row -> kept.add(row.id()));
        for (String id : Set.copyOf(categories.keySet())) {
            if (!kept.contains(id)) {
                categories.remove(id);
                repository.delete(id);
            }
        }
        rows.forEach(row -> categories.put(row.id(), row));
        repository.saveAll(rows);
    }
}
