package net.exylia.lib.util.loot;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.text.Text;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.util.wizard.Wizards;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How a loot line draws and edits itself on screen.
 *
 * <p>Handed to the list editor by {@link Loot#editor}; a plugin does not
 * normally construct one.
 *
 * <h2>One bucket for every loot table in the ecosystem</h2>
 * A table copied out of a loot chest pastes into a spawner, and into an event's
 * pool, because they are the same rows in the same format. That is the whole
 * point of a shared clipboard key.
 *
 * @since 1.56.0
 */
public final class LootDescriptor implements EditorDescriptor<LootEntry> {

    /** The clipboard bucket every loot table shares. */
    public static final String TYPE_KEY = "exylia:loot";

    private static final FormKey<String> COMMAND = FormKey.text("command");
    private static final FormKey<Boolean> REPLACE = FormKey.flag("replace");
    private static final FormKey<Long> MINIMUM = FormKey.integer("minimum");
    private static final FormKey<Long> MAXIMUM = FormKey.integer("maximum");
    private static final FormKey<BigDecimal> WEIGHT = FormKey.decimal("weight");
    private static final FormKey<String> TIER = FormKey.text("tier");

    private final Plugin plugin;

    LootDescriptor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String label(@NotNull LootEntry entry) {
        return "{primary}&l" + entry.displayName().toUpperCase(Locale.ROOT);
    }

    @Override
    public @NotNull String icon(@NotNull LootEntry entry) {
        return entry.resolvedIcon();
    }

    @Override
    public @NotNull List<String> lore(@NotNull LootEntry entry) {
        return lore(entry, List.of(entry));
    }

    /**
     * The same, plus what this line is worth against the others.
     *
     * <p>A weight read alone says nothing: a weight of one is common in a table
     * of three and rare in a table of forty. The extra line is the line's share
     * of everything the table drops, which is what an admin is balancing.
     */
    @Override
    public @NotNull List<String> lore(@NotNull LootEntry entry,
                                      @NotNull List<LootEntry> siblings) {
        List<String> lore = new ArrayList<>(8);
        lore.add("{secondary}Gives:");
        if (entry.isCommand()) {
            lore.add(" {letters_black}▎ {letters}Command {letters_black}» {warning}"
                    + entry.displayName());
        } else {
            lore.add(" {letters_black}▎ {letters}Amount {letters_black}» {info}" + entry.minAmount()
                    + (entry.isRanged() ? " {muted}— {info}" + entry.maxAmount() : ""));
        }
        lore.add("");
        lore.add("{secondary}Odds:");
        lore.add(" {letters_black}▎ {letters}Weight 🎲 {letters_black}» {highlight}"
                + number(entry.weight()));
        String share = share(entry.weight(), siblings);
        if (share != null) {
            lore.add(" {letters_black}▎ {letters}Real {letters_black}» {success}" + share
                    + "% {muted}of all drops");
        }
        if (entry.tier() != null && !entry.tier().isBlank()) {
            lore.add(" {letters_black}▎ {letters}Tier {letters_black}» {info}" + entry.tier());
        }
        return lore;
    }

    @Override
    public @NotNull LootEntry create() {
        return LootEntry.of(LootType.ITEM).build();
    }

    /**
     * Asks what the press of add meant: an item, a command, or a whole chest.
     *
     * <p>The third is the one ExyliaCommons had and the migration lost. It was
     * never a loot table feature there — every plugin that wanted it wrote its
     * own wand, its own pending-import map and its own listener — so it lives
     * here, once, and every loot table in the ecosystem has it.
     */
    @Override
    public @NotNull CompletionStage<List<LootEntry>> createAll(@NotNull Player viewer) {
        return Inputs.of(plugin).choice(viewer, "{primary}&lWHAT DO YOU WANT TO ADD?",
                        List.of(Adding.values()))
                .label(Adding::label)
                .icon(Adding::icon)
                .key(Enum::name)
                .open()
                .thenCompose(result -> {
                    if (!result.completed()) {
                        return CompletableFuture.completedFuture(List.<LootEntry>of());
                    }
                    return switch (result.value()) {
                        case ITEM -> one(LootType.ITEM);
                        case COMMAND -> one(LootType.COMMAND);
                        case CHEST -> fromChest(viewer);
                    };
                });
    }

    private static CompletionStage<List<LootEntry>> one(LootType type) {
        return CompletableFuture.completedFuture(List.of(LootEntry.of(type).build()));
    }

    /**
     * Every item in a container the viewer clicks, as loot lines.
     *
     * <p>The chest is read inside the click, on the thread that just delivered
     * it: on Folia the block belongs to a region, and a read scheduled for
     * later is a read from the wrong thread.
     *
     * <p>Waits on the run rather than on the accepted callback, because a
     * player who wandered off has to release the editor too — a timeout that
     * left the screen closed would be worse than an empty import.
     */
    private CompletionStage<List<LootEntry>> fromChest(Player viewer) {
        AtomicReference<List<LootEntry>> imported = new AtomicReference<>(List.of());
        return Wizards.of(plugin)
                .askPoint(viewer, "{primary}&lIMPORT FROM A CHEST",
                        "{warning}➥ {letters}Left-click the chest you want to copy.",
                        where -> imported.set(read(plugin, viewer, where)),
                        null)
                .result()
                .thenApply(ended -> imported.get());
    }

    private static List<LootEntry> read(Plugin plugin, Player viewer, Location where) {
        if (!(where.getBlock().getState() instanceof Container container)) {
            Text.from(plugin, "{error}That block holds nothing to import.").send(viewer);
            return List.of();
        }
        List<LootEntry> entries = new ArrayList<>();
        for (ItemStack item : container.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) {
                continue;
            }
            // A hundred, not the library's fifty: somebody importing a chest
            // is describing the chest they built, and every line of it drops.
            // The same number ExyliaCommons imported with.
            entries.add(Loot.entryOf(item).amountBetween(1, item.getAmount()).weight(100.0).build());
        }
        Text.from(plugin, "{success}Imported {info}" + entries.size()
                + " {success}line" + (entries.size() == 1 ? "" : "s") + ".").send(viewer);
        return entries;
    }

    @Override
    public @NotNull LootEntry copy(@NotNull LootEntry entry) {
        return entry.copy();
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    @Override
    public boolean isComplete(@NotNull LootEntry entry) {
        return entry.isCommand()
                ? entry.command() != null && !entry.command().isBlank()
                : entry.itemSnapshot() != null;
    }

    /**
     * Edits the line's properties, not the item it gives.
     *
     * <p>Editing used to mean "insert the item again": an admin who wanted to
     * move a weight from 50 to 40 had to find the item, put it back in a slot
     * and only then get to the numbers, and an item they could no longer
     * produce was a line they could no longer touch. The item is now one field
     * of the form like any other, and only a line that has no item yet — the
     * one add just made — is asked for it first, because there is nothing to
     * edit the properties of.
     */
    @Override
    public @NotNull CompletionStage<Optional<LootEntry>> edit(@NotNull Player viewer,
                                                              @NotNull LootEntry entry) {
        if (entry.isItem() && entry.itemSnapshot() == null) {
            return pick(viewer, entry).thenCompose(picked -> picked.isPresent()
                    ? form(viewer, picked.get())
                    : CompletableFuture.completedFuture(Optional.<LootEntry>empty()));
        }
        return form(viewer, entry);
    }

    /** Opens the one-slot window and puts whatever is inserted on the line. */
    private CompletionStage<Optional<LootEntry>> pick(Player viewer, LootEntry entry) {
        return Inputs.of(plugin).icon(viewer, "{primary}&lWHICH ITEM?")
                .open()
                .thenApply(icon -> icon.completed()
                        ? Optional.of(entry.toBuilder().itemSnapshot(icon.value()).build())
                        : Optional.<LootEntry>empty());
    }

    private CompletionStage<Optional<LootEntry>> form(Player viewer, LootEntry entry) {
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lEDIT LOOT");
        boolean command = entry.isCommand();
        if (command) {
            form.text(COMMAND, "Command the console runs", entry.command(), 3)
                    .hint("%player_name% is the player, no leading slash");
        } else {
            form.integer(MINIMUM, "Least amount", entry.minAmount())
                    .integer(MAXIMUM, "Most amount", entry.maxAmount());
        }
        form.decimal(WEIGHT, "Weight", BigDecimal.valueOf(entry.weight()))
                .text(TIER, "Tier (blank for none)", entry.tier());
        if (!command) {
            form.flag(REPLACE, "Put a different item in", false)
                    .hint("Leave it off to keep the item this line already gives");
        }

        return form.ask(values -> new Edited(rebuild(entry, values, command),
                        !command && values.getBoolean(REPLACE)))
                .thenCompose(answered -> {
                    if (answered.isEmpty()) {
                        return CompletableFuture.completedFuture(Optional.<LootEntry>empty());
                    }
                    Edited edited = answered.get();
                    if (!edited.replaceItem()) {
                        return CompletableFuture.completedFuture(Optional.of(edited.entry()));
                    }
                    // Backing out of the window keeps the numbers just answered:
                    // they were a separate question, and losing them would make
                    // the flag a trap.
                    return pick(viewer, edited.entry())
                            .thenApply(picked -> picked.or(() -> Optional.of(edited.entry())));
                });
    }

    /** The form's two answers: what the line became, and whether to reopen the slot. */
    private record Edited(LootEntry entry, boolean replaceItem) { }

    /** What one press of add can put in a loot table. */
    private enum Adding {

        ITEM("{primary}&lAN ITEM", LootType.ITEM.defaultIcon()),
        COMMAND("{primary}&lA COMMAND", LootType.COMMAND.defaultIcon()),
        CHEST("{highlight}&lEVERYTHING IN A CHEST", "CHEST");

        private final String label;
        private final String icon;

        Adding(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        String label() {
            return label;
        }

        Material icon() {
            Material material = Material.matchMaterial(icon);
            return material == null ? Material.PAPER : material;
        }
    }

    private static LootEntry rebuild(LootEntry entry, FormValues values, boolean command) {
        LootEntry.Builder builder = entry.toBuilder()
                .weight(values.getDecimal(WEIGHT).doubleValue())
                .tier(blankToNull(values.getText(TIER)));
        if (command) {
            builder.command(blankToNull(values.getText(COMMAND)));
        } else {
            // Put in order rather than refused: somebody who typed them the
            // wrong way round meant a range, and losing the line teaches them
            // nothing at a time nobody is reading the log.
            builder.amountBetween((int) Math.max(1, values.getLong(MINIMUM)),
                    (int) Math.max(1, values.getLong(MAXIMUM)));
        }
        return builder.build();
    }

    /**
     * A line's share of everything the table drops, as a percentage.
     *
     * <p>The same number under both readings of a weight: as a per-line
     * percentage the shares are the expected counts normalised, and as a share
     * of the total they are the weights normalised. Nothing is drawn for a table
     * of one, nor for one nothing can ever drop out of.
     *
     * @return the share, or {@code null} when there is no useful one
     */
    private static String share(double weight, List<LootEntry> siblings) {
        if (siblings.size() < 2) {
            return null;
        }
        double total = 0.0;
        for (LootEntry sibling : siblings) {
            total += Math.max(0.0, sibling.weight());
        }
        if (total <= 0.0) {
            return null;
        }
        return number(Math.round(Math.max(0.0, weight) * 1000.0 / total) / 10.0);
    }

    private static String number(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
