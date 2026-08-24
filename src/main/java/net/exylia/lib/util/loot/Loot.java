package net.exylia.lib.util.loot;

import net.exylia.lib.util.editor.Editors;
import net.exylia.lib.util.editor.ListEditor;
import net.exylia.lib.util.loot.internal.LootItems;
import net.exylia.lib.util.loot.internal.LootLines;
import net.exylia.lib.util.loot.internal.LootRolls;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * What comes out of a chest, a spawner or a broken block.
 *
 * <pre>{@code
 * List<LootEntry> table = Loot.parseAll(config.pool());
 *
 * for (ItemStack item : Loot.roll(table)) {
 *     chest.getInventory().addItem(item);
 * }
 * }</pre>
 *
 * <h2>Two readings of one weight, and both are deliberate</h2>
 * A loot table in this ecosystem means one of two things, and which one is the
 * caller's to say:
 *
 * <ul>
 *   <li>{@link #roll} rolls <em>every</em> line against its weight as a
 *       percentage. Any number of them come up. This is a chest and a spawner.
 *   <li>{@link #pick} picks <em>one</em> line, each weight being its share of
 *       the total. This is a survival-games refill, one item per slot.
 * </ul>
 *
 * <p>The same stored number, because that is what the tables already out there
 * mean by it.
 *
 * <h2>Threads</h2>
 * Everything here is computation over strings and numbers, and safe from any
 * thread — which is the point: a hundred chests are rolled off the main thread
 * and only the finished items are handed back to it. Putting an item into an
 * inventory, dropping it in the world or running a command is the caller's, and
 * belongs on the thread that owns that block or that player.
 *
 * <h2>Nothing is held</h2>
 * This module keeps no state, no cache and no registry: a table is a list the
 * caller owns and rolling it allocates only the result. There is nothing to
 * release when a plugin disables, and nothing to invalidate when the palette
 * reloads.
 *
 * @since 1.56.0
 */
public final class Loot {

    private Loot() {
        throw new AssertionError("No instances.");
    }

    /** Ignores what it cannot read, which is what a stored table deserves. */
    private static final BiConsumer<String, String> SILENT = (line, problem) -> { };

    // ---------------------------------------------------------------- editing

    /**
     * A screen for editing a loot table.
     *
     * <pre>{@code
     * Loot.editor(this, template.entries())
     *     .title("{primary}&lLOOT TABLE")
     *     .onSave(entries -> manager.save(template, entries))
     *     .onCancel(() -> setupMenu.open(player))
     *     .open(player);
     * }</pre>
     *
     * <p>Pagination, add, edit, delete, copy, paste, save and cancel, over the
     * one screen every list editor in the library shares. A table copied here
     * pastes into any other loot editor — a chest into a spawner, a spawner into
     * an event — because they are the same rows in the same format.
     *
     * @param plugin  the plugin the screen belongs to
     * @param entries what is being edited; copied, never held
     * @return the editor, ready to open
     * @since 1.56.0
     */
    public static @NotNull ListEditor<LootEntry> editor(@NotNull Plugin plugin,
                                                        @NotNull List<LootEntry> entries) {
        return Editors.of(plugin).list(new LootDescriptor(plugin), LootEntry.class, entries);
    }

    // ---------------------------------------------------------------- writing

    /**
     * Reads a loot pool written the compact way a config file writes one.
     *
     * <pre>{@code
     * MATERIAL MIN MAX WEIGHT [TIER]
     * DIAMOND_SWORD 1 1 5 RARE
     * SPLASH:HEALING 1 2 20
     * }</pre>
     *
     * <p>A line that cannot be read is skipped, not fatal: one typo in a
     * fifty-line pool costs that line, and refusing the file would cost the
     * event.
     *
     * @param lines the pool as written
     * @return the entries that could be read, never {@code null}
     */
    public static @NotNull List<LootEntry> parseAll(@Nullable List<String> lines) {
        return parseAll(lines, SILENT);
    }

    /**
     * The same, reporting what it had to skip.
     *
     * <p>For a config file, where somebody typed it and can fix it. Pass a
     * plugin's own {@code debug::warn} and the typo lands in its console with
     * its name on it.
     *
     * @param lines    the pool as written
     * @param problems told the line and what was wrong with it
     * @return the entries that could be read
     */
    public static @NotNull List<LootEntry> parseAll(@Nullable List<String> lines,
                                                    @NotNull BiConsumer<String, String> problems) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<LootEntry> entries = new ArrayList<>(lines.size());
        for (String line : lines) {
            LootEntry entry = LootLines.parse(line, LootItems.BUKKIT, problems);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Reads one line of a pool.
     *
     * @param line the line as written
     * @return the entry, or {@code null} when the line could not be read
     */
    public static @Nullable LootEntry parse(@Nullable String line) {
        return LootLines.parse(line, LootItems.BUKKIT, SILENT);
    }

    /**
     * The same, reporting what was wrong.
     *
     * @param line     the line as written
     * @param problems told the line and what was wrong with it
     * @return the entry, or {@code null}
     */
    public static @Nullable LootEntry parse(@Nullable String line,
                                            @NotNull BiConsumer<String, String> problems) {
        return LootLines.parse(line, LootItems.BUKKIT, problems);
    }

    // --------------------------------------------------------------- rolling

    /**
     * Rolls every line of a table and returns what came up, shuffled.
     *
     * <p>Never empty for a table that is not: if no line came up, one is forced,
     * because a chest that opens empty reads as broken. Use
     * {@link #roll(List, boolean)} where nothing is a legitimate answer.
     *
     * <p>Shuffled because the caller is about to spread these across a chest,
     * and unshuffled they would land in the order the file wrote them — every
     * chest on the map identical.
     *
     * @param entries the table
     * @return the items, ready to place
     */
    public static @NotNull List<ItemStack> roll(@NotNull List<LootEntry> entries) {
        return roll(entries, true);
    }

    /**
     * The same, saying whether an empty result is allowed.
     *
     * @param entries         the table
     * @param forceOneIfEmpty {@code false} when producing nothing is a real
     *                        answer, as it is for a spawner tick
     * @return the items, possibly none
     */
    public static @NotNull List<ItemStack> roll(@NotNull List<LootEntry> entries,
                                                boolean forceOneIfEmpty) {
        List<ItemStack> items = items(LootRolls.independent(entries, forceOneIfEmpty, LootRolls.RANDOM));
        LootRolls.RANDOM.shuffle(items);
        return items;
    }

    /**
     * Rolls every line and returns the entries themselves.
     *
     * <p>For a table that can hold {@link LootType#COMMAND} lines, which have no
     * item: the caller decides what each kind means, giving one and running the
     * other. {@link #roll} resolves items and would silently drop them.
     *
     * @param entries the table
     * @return the entries that came up, in the order they were written
     */
    public static @NotNull List<LootEntry> rollEntries(@NotNull List<LootEntry> entries) {
        return LootRolls.independent(entries, true, LootRolls.RANDOM);
    }

    /**
     * Picks one line by weight and builds its item.
     *
     * @param entries the table
     * @return the item, or {@code null} if nothing could be picked or built
     */
    public static @Nullable ItemStack pick(@NotNull List<LootEntry> entries) {
        LootEntry picked = pickEntry(entries);
        return picked == null ? null : itemOf(picked);
    }

    /**
     * Picks one line by weight.
     *
     * @param entries the table
     * @return the winner, or {@code null} when every weight is zero or the
     *         table is empty
     */
    public static @Nullable LootEntry pickEntry(@NotNull List<LootEntry> entries) {
        return LootRolls.pick(entries, LootRolls.RANDOM);
    }

    // ----------------------------------------------------------------- items

    /**
     * Builds an entry's item, at a freshly rolled stack size.
     *
     * <p>Never zero of something: an entry whose stored amounts say zero, or say
     * a range the wrong way round, gives one. ExyliaCommons handed back a stack
     * of zero for the first and the item vanished on its way into the chest.
     *
     * @param entry the entry
     * @return the item, or {@code null} for a command entry or an unreadable one
     */
    public static @Nullable ItemStack itemOf(@NotNull LootEntry entry) {
        if (!entry.isItem() || entry.itemSnapshot() == null) {
            return null;
        }
        ItemStack item = LootItems.BUKKIT.build(entry.itemSnapshot());
        if (item == null) {
            return null;
        }
        item.setAmount(amountOf(entry));
        return item;
    }

    /**
     * Rolls how many an entry gives, without building it.
     *
     * @param entry the entry
     * @return the stack size, never below one
     */
    public static int amountOf(@NotNull LootEntry entry) {
        return LootRolls.amount(entry, LootRolls.RANDOM);
    }

    /**
     * An entry for an item somebody is holding.
     *
     * <p>What an editor's "add the item in my hand" button stores. The amounts
     * default to one and the weight to {@link LootEntry#DEFAULT_WEIGHT}, as they
     * did in ExyliaCommons; the builder is returned so the row that is about to
     * be edited can be given its odds in the same breath.
     *
     * @param item the item
     * @return a builder for the entry
     */
    public static @NotNull LootEntry.Builder entryOf(@NotNull ItemStack item) {
        return LootEntry.item(snapshotOf(item));
    }

    /**
     * Stores an item the way a loot table stores one.
     *
     * <p>Byte-identical to what ExyliaCommons wrote, so a table this library
     * saves diffs clean against one the old module saved.
     *
     * @param item the item
     * @return the stored form
     */
    public static @NotNull String snapshotOf(@NotNull ItemStack item) {
        return LootItems.BUKKIT.snapshot(item);
    }

    private static List<ItemStack> items(List<LootEntry> entries) {
        List<ItemStack> items = new ArrayList<>(entries.size());
        for (LootEntry entry : entries) {
            ItemStack item = itemOf(entry);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }
}
