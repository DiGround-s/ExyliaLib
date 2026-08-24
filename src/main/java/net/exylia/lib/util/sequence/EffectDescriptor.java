package net.exylia.lib.util.sequence;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * How an effect draws and edits itself on screen.
 *
 * <p>Handed to the list editor by {@link PluginSequences#editor}.
 *
 * <h2>One form, not eight menus</h2>
 * ExyliaCommons needed a type-select menu and then a different screen per type,
 * because its entry carried a field per property of every type it knew. Here the
 * payload is text — the sequence notation — so an effect is its gating plus a
 * box of lines, whatever it happens to do.
 *
 * @since 1.57.0
 */
final class EffectDescriptor implements EditorDescriptor<EffectEntry> {

    /** The clipboard bucket effect lists share. */
    static final String TYPE_KEY = "exylia:effects";

    /** How tall the box of lines is: enough to see a short sequence whole. */
    private static final int LINE_BOX = 8;

    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<String> LINES = FormKey.text("lines");
    private static final FormKey<BigDecimal> CHANCE = FormKey.decimal("chance");
    private static final FormKey<String> CONDITION = FormKey.text("condition");
    private static final FormKey<String> PERMISSION = FormKey.text("permission");
    private static final FormKey<Long> PRIORITY = FormKey.integer("priority");
    private static final FormKey<Long> DELAY = FormKey.integer("delay");
    private static final FormKey<String> RADIUS = FormKey.text("radius");

    private final Plugin plugin;

    EffectDescriptor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
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
        return iconFor(entry.lines().isEmpty() ? "" : entry.lines().get(0));
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

    @Override
    public @NotNull CompletionStage<Optional<EffectEntry>> edit(@NotNull Player viewer,
                                                                @NotNull EffectEntry entry) {
        return EditorForm.of(plugin, viewer, "{primary}&lEDIT EFFECT")
                .text(NAME, "Name (blank to show the first line)", entry.name(), 2)
                .text(LINES, "What it plays, one line each", String.join("\n", entry.lines()), LINE_BOX)
                .decimal(CHANCE, "Chance out of 100", BigDecimal.valueOf(entry.chance()))
                .text(RADIUS, "Seen by: a radius, 0 for them alone, or world", written(entry))
                .integer(DELAY, "Ticks to wait first", entry.delayTicks())
                .integer(PRIORITY, "Priority, higher plays first", entry.priority())
                .text(CONDITION, "Condition (blank for none)", entry.condition(), 2)
                .text(PERMISSION, "Permission (blank for none)", entry.permission())
                .ask(values -> rebuild(entry, values));
    }

    private static EffectEntry rebuild(EffectEntry entry, FormValues values) {
        return entry.toBuilder()
                .name(blankToNull(values.getText(NAME)))
                .lines(lines(values.getText(LINES)))
                .chance(values.getDecimal(CHANCE).doubleValue())
                .radius(radius(values.getText(RADIUS)))
                .delayTicks(values.getLong(DELAY))
                .priority((int) values.getLong(PRIORITY))
                .condition(blankToNull(values.getText(CONDITION)))
                .permission(blankToNull(values.getText(PERMISSION)))
                .build();
    }

    /**
     * The box of lines, split back apart.
     *
     * <p>Blank lines are dropped rather than kept: a tall box invites a trailing
     * newline, and a sequence line that is nothing at all is a compile warning
     * an admin never asked to see.
     */
    private static List<String> lines(String written) {
        if (written == null || written.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : written.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
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
            // throw away the other seven.
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

    /**
     * A material that says what a line does, read from its keyword alone.
     *
     * <p>Deliberately not a lookup of the effect itself: a page draws forty-five
     * of these and redraws after every click, and the keyword is enough to tell a
     * sound from a title at a glance.
     */
    private static String iconFor(String line) {
        String keyword = keyword(line);
        return switch (keyword) {
            case "SOUND" -> "NOTE_BLOCK";
            case "PARTICLE" -> "FIREWORK_ROCKET";
            case "POTION" -> "POTION";
            case "FIREWORK" -> "FIREWORK_STAR";
            case "TITLE" -> "OAK_SIGN";
            case "ACTION_BAR" -> "PAPER";
            case "MESSAGE" -> "WRITTEN_BOOK";
            case "COMMAND" -> "COMMAND_BLOCK";
            case "LIGHTNING" -> "LIGHTNING_ROD";
            case "EXPLOSION" -> "TNT";
            case "BLOCK_BREAK" -> "IRON_PICKAXE";
            case "DELAY" -> "CLOCK";
            case "" -> "BARRIER";
            default -> "BLAZE_POWDER";
        };
    }

    private static String keyword(String line) {
        int open = line.indexOf('[');
        int close = line.indexOf(']');
        if (open != 0 || close <= 1) {
            return "";
        }
        return line.substring(1, close).toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
