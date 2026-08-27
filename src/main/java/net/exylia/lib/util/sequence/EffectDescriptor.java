package net.exylia.lib.util.sequence;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.util.editor.EditorButton;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.util.editor.Editors;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How an effect draws and edits itself on screen.
 *
 * <p>Handed to the list editor by {@link PluginSequences#editor}.
 *
 * <h2>Two screens, not eight menus</h2>
 * ExyliaCommons needed a type-select menu and then a different screen per type,
 * because its entry carried a field per property of every type it knew. Here an
 * effect is its gating plus a list of sequence lines, and both are the editor
 * that already exists: clicking an effect opens its lines as their own list —
 * where each line is added by picking what it plays and searching for it — and
 * the gating sits behind one button on that screen.
 *
 * <p>So the type-select menu is back, and it is the only screen anybody asked
 * for: what it lost was the eight-way {@code switch} behind it, and what it
 * gained is every token the sequence notation has, shapes included.
 *
 * @since 1.57.0
 */
final class EffectDescriptor implements EditorDescriptor<EffectEntry> {

    /** The clipboard bucket effect lists share. */
    static final String TYPE_KEY = "exylia:effects";

    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<BigDecimal> CHANCE = FormKey.decimal("chance");
    private static final FormKey<String> CONDITION = FormKey.text("condition");
    private static final FormKey<String> PERMISSION = FormKey.text("permission");
    private static final FormKey<Long> PRIORITY = FormKey.integer("priority");
    private static final FormKey<Long> DELAY = FormKey.integer("delay");
    private static final FormKey<String> RADIUS = FormKey.text("radius");

    private final Plugin plugin;
    private final Set<String> shapeNames;

    EffectDescriptor(Plugin plugin, Set<String> shapeNames) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.shapeNames = Set.copyOf(Objects.requireNonNull(shapeNames, "shapeNames"));
    }

    @Override
    public @NotNull String label(@NotNull EffectEntry entry) {
        return "{primary}&l" + entry.displayName().toUpperCase(Locale.ROOT);
    }

    /**
     * What the effect is drawn as.
     *
     * <p>The explicit icon wins; otherwise the first line names it, so a list of
     * effects reads as sounds, particles and titles at a glance rather than as
     * forty identical pieces of paper.
     */
    @Override
    public @NotNull String icon(@NotNull EffectEntry entry) {
        if (entry.icon() != null && !entry.icon().isBlank()) {
            return entry.icon();
        }
        return SequenceLine.icon(entry.lines().isEmpty()
                ? "" : SequenceLine.of(entry.lines().get(0)).token());
    }

    @Override
    public @NotNull List<String> lore(@NotNull EffectEntry entry) {
        List<String> lore = new ArrayList<>(10);
        lore.add("{secondary}Plays:");
        if (entry.lines().isEmpty()) {
            lore.add(" {letters_black}▎ {muted}nothing yet");
        } else {
            for (int index = 0; index < Math.min(4, entry.lines().size()); index++) {
                lore.add(" {letters_black}▎ {letters}" + entry.lines().get(index));
            }
            if (entry.lines().size() > 4) {
                lore.add(" {letters_black}▎ {muted}and " + (entry.lines().size() - 4) + " more");
            }
        }
        lore.add("");
        lore.add("{secondary}When:");
        lore.add(" {letters_black}▎ {letters}Chance {letters_black}» " + chance(entry));
        lore.add(" {letters_black}▎ {letters}Seen by {letters_black}» {info}" + audience(entry));
        if (entry.delayTicks() > 0) {
            lore.add(" {letters_black}▎ {letters}After {letters_black}» {info}"
                    + entry.delayTicks() / 20.0 + "s ⏱️");
        }
        if (entry.condition() != null) {
            lore.add(" {letters_black}▎ {letters}If {letters_black}» {info}" + entry.condition());
        }
        if (entry.permission() != null) {
            lore.add(" {letters_black}▎ {letters}Needs {letters_black}» {info}" + entry.permission());
        }
        return lore;
    }

    @Override
    public @NotNull EffectEntry create() {
        return EffectEntry.blank();
    }

    /**
     * Adding an effect is adding its first line.
     *
     * <p>An effect with no lines does nothing, so the add flow starts where a
     * line does: what it plays, which one, and its settings. The lines screen
     * opens straight afterwards with that line already on it, which is where a
     * second one gets added.
     */
    @Override
    public @NotNull CompletionStage<Optional<EffectEntry>> create(@NotNull Player viewer) {
        LineDescriptor lines = new LineDescriptor(plugin, shapeNames);
        return lines.create(viewer).thenCompose(chosen -> {
            if (chosen.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<EffectEntry>empty());
            }
            return lines.edit(viewer, chosen.get()).thenApply(configured -> configured
                    .map(line -> EffectEntry.of(List.of(line.text())).build()));
        });
    }

    @Override
    public @NotNull EffectEntry copy(@NotNull EffectEntry entry) {
        return entry.copy();
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    @Override
    public boolean isComplete(@NotNull EffectEntry entry) {
        return entry.isPlayable();
    }

    /**
     * Opens the effect's lines as their own editor.
     *
     * <p>Nothing is written until that screen is saved, and its cancel throws
     * away the gating changes too: the two halves of an effect are edited on one
     * screen, so they are kept or dropped together.
     */
    @Override
    public @NotNull CompletionStage<Optional<EffectEntry>> edit(@NotNull Player viewer,
                                                                @NotNull EffectEntry entry) {
        CompletableFuture<Optional<EffectEntry>> answer = new CompletableFuture<>();
        // The gating lives here while the lines are being edited: the line list is
        // the editor's working copy, and an effect is more than its lines.
        AtomicReference<EffectEntry> gating = new AtomicReference<>(entry);

        List<SequenceLine> lines = new ArrayList<>(entry.lines().size());
        for (String line : entry.lines()) {
            lines.add(SequenceLine.of(line));
        }

        Editors.of(plugin)
                .list(new LineDescriptor(plugin, shapeNames), SequenceLine.class, lines)
                .title(title(entry))
                .button(settings(viewer, gating))
                .onSave(edited -> answer.complete(Optional.of(rebuild(gating.get(), edited))))
                .onCancel(() -> answer.complete(Optional.empty()))
                .open(viewer);
        return answer;
    }

    /**
     * What the lines screen is called.
     *
     * <p>The effect's name when it has one; otherwise what the screen is, not
     * the first line of notation — {@code displayName()} falls back to that, and
     * a window titled {@code [CIRCLE] FLAME;RADIUS:1.5} tells nobody anything.
     */
    private static String title(EffectEntry entry) {
        String name = entry.name();
        return name == null || name.isBlank()
                ? "{primary}&lWHAT IT PLAYS"
                : "{primary}&l" + name.toUpperCase(Locale.ROOT);
    }

    /** The one button on the lines screen: everything an effect is besides them. */
    private EditorButton<SequenceLine> settings(Player viewer, AtomicReference<EffectEntry> gating) {
        return EditorButton.<SequenceLine>of("COMPARATOR")
                .name("{highlight}&lWHEN IT PLAYS")
                .lore("{secondary}Information:",
                        " {letters_black}▎ {letters}Its odds, who sees it, how long",
                        " {letters_black}▎ {letters}it waits, and what it needs.",
                        "",
                        "{warning}➥ Click to change, then save")
                .glowing()
                .onClick(view -> view.ask(() -> form(viewer, gating.get())
                        .thenAccept(edited -> edited.ifPresent(gating::set))))
                .build();
    }

    /** The gating, as one prefilled dialog. */
    private CompletionStage<Optional<EffectEntry>> form(Player viewer, EffectEntry entry) {
        return EditorForm.of(plugin, viewer, "{primary}&lWHEN IT PLAYS")
                .text(NAME, "Name (blank to show the first line)", entry.name(), 2)
                .decimal(CHANCE, "Chance out of 100", BigDecimal.valueOf(entry.chance()))
                .text(RADIUS, "Seen by: a radius, 0 for them alone, or world", written(entry))
                .integer(DELAY, "Ticks to wait first", entry.delayTicks())
                .integer(PRIORITY, "Priority, higher plays first", entry.priority())
                .text(CONDITION, "Condition (blank for none)", entry.condition(), 2)
                .text(PERMISSION, "Permission (blank for none)", entry.permission())
                .ask(values -> regate(entry, values));
    }

    private static EffectEntry regate(EffectEntry entry, FormValues values) {
        return entry.toBuilder()
                .name(blankToNull(values.getText(NAME)))
                .chance(values.getDecimal(CHANCE).doubleValue())
                .radius(radius(values.getText(RADIUS)))
                .delayTicks(values.getLong(DELAY))
                .priority((int) values.getLong(PRIORITY))
                .condition(blankToNull(values.getText(CONDITION)))
                .permission(blankToNull(values.getText(PERMISSION)))
                .build();
    }

    /**
     * The effect as the lines screen left it.
     *
     * <p>Blank lines cannot happen here — a row is a line somebody chose a token
     * for — but a line whose token nobody recognises still comes back exactly as
     * it was written, because deleting what a screen could not describe is how
     * an editor eats a working config.
     */
    private static EffectEntry rebuild(EffectEntry gating, List<SequenceLine> edited) {
        List<String> lines = new ArrayList<>(edited.size());
        for (SequenceLine line : edited) {
            if (!line.text().isBlank()) {
                lines.add(line.text());
            }
        }
        return gating.toBuilder().lines(lines).build();
    }

    private static double radius(String written) {
        if (written == null || written.isBlank()) {
            return EffectEntry.DEFAULT_RADIUS;
        }
        String text = written.trim();
        if (text.equalsIgnoreCase("world")) {
            return EffectEntry.WHOLE_WORLD;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException notANumber) {
            // What they typed was not a number and was not the word. The default
            // is the honest answer: refusing the whole edit over one field would
            // throw away the other six.
            return EffectEntry.DEFAULT_RADIUS;
        }
    }

    private static String written(EffectEntry entry) {
        if (entry.radius() == EffectEntry.WHOLE_WORLD) {
            return "world";
        }
        return String.valueOf(entry.radius());
    }

    private static String audience(EffectEntry entry) {
        if (entry.isPrivate()) {
            return "them alone";
        }
        if (entry.radius() == EffectEntry.WHOLE_WORLD) {
            return "the whole world";
        }
        return entry.radius() + " blocks";
    }

    private static String chance(EffectEntry entry) {
        return entry.isGuaranteed()
                ? "{success}always"
                : "{highlight}" + entry.chance() + "%";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
