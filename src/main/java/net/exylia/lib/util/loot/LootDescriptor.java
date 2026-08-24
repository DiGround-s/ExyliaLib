package net.exylia.lib.util.loot;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.util.editor.Editors;
import org.bukkit.Material;
import org.bukkit.entity.Player;
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
        List<String> lore = new ArrayList<>(7);
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
        if (entry.tier() != null && !entry.tier().isBlank()) {
            lore.add(" {letters_black}▎ {letters}Tier {letters_black}» {info}" + entry.tier());
        }
        return lore;
    }

    @Override
    public @NotNull LootEntry create() {
        return LootEntry.of(LootType.ITEM).build();
    }

    /** Asks whether the line gives an item or runs a command. */
    @Override
    public @NotNull CompletionStage<Optional<LootEntry>> create(@NotNull Player viewer) {
        return Inputs.of(plugin).choice(viewer, "{primary}&lWHAT DOES IT GIVE?",
                        List.of(LootType.values()))
                .label(type -> type == LootType.ITEM ? "{primary}&lAN ITEM" : "{primary}&lA COMMAND")
                .icon(LootDescriptor::iconOf)
                .key(Enum::name)
                .open()
                .thenApply(result -> result.completed()
                        ? Optional.of(LootEntry.of(result.value()).build())
                        : Optional.empty());
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

    @Override
    public @NotNull CompletionStage<Optional<LootEntry>> edit(@NotNull Player viewer,
                                                              @NotNull LootEntry entry) {
        if (entry.isItem()) {
            return Editors.of(plugin).icon()
                    .title("{primary}&lWHAT ITEM?")
                    .open(viewer)
                    .thenCompose(icon -> icon.isEmpty()
                            ? CompletableFuture.completedFuture(Optional.<LootEntry>empty())
                            : form(viewer, entry.toBuilder().itemSnapshot(icon.get()).build()));
        }
        return form(viewer, entry);
    }

    private CompletionStage<Optional<LootEntry>> form(Player viewer, LootEntry entry) {
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lEDIT LOOT");
        if (entry.isCommand()) {
            form.text(COMMAND, "Command the console runs", entry.command(), 3);
        } else {
            form.integer(MINIMUM, "Least amount", entry.minAmount())
                    .integer(MAXIMUM, "Most amount", entry.maxAmount());
        }
        form.decimal(WEIGHT, "Weight", BigDecimal.valueOf(entry.weight()))
                .text(TIER, "Tier (blank for none)", entry.tier());

        boolean command = entry.isCommand();
        return form.ask(values -> rebuild(entry, values, command));
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

    private static Material iconOf(LootType type) {
        Material material = Material.matchMaterial(type.defaultIcon());
        return material == null ? Material.PAPER : material;
    }

    private static String number(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
