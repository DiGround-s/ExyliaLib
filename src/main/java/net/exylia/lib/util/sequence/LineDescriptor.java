package net.exylia.lib.util.sequence;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.util.editor.Editors;
import net.exylia.lib.util.editor.Pickers;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * How one line of a sequence draws and edits itself.
 *
 * <p>Handed to the list editor by {@link EffectDescriptor}, which edits an
 * effect's payload as a list of these rather than as a block of notation.
 *
 * <h2>Adding is two searches, never a syntax</h2>
 * The notation is the storage format, not the interface to it. Adding a line
 * asks what it plays and then which one — both as the search screen the icon
 * picker already uses — and only then opens a form, whose fields are the ones
 * that token actually reads. Nobody types {@code [CIRCLE] FLAME;radius:1.5} to
 * get a circle of flame, and nobody has to remember that {@code radius} is
 * spelled that way on a circle and {@code span} on a pair of wings.
 *
 * <p>What is written is still exactly that line, so a file authored by hand and
 * a line built by clicking are the same thing, and either can edit the other.
 *
 * @since 1.71.0
 */
final class LineDescriptor implements EditorDescriptor<SequenceLine> {

    /** How tall the box is for a line that is prose. */
    private static final int PROSE_BOX = 3;

    /** What a line's first segment is asked for as, per kind of head. */
    private static final FormKey<String> HEAD = FormKey.text("head");

    private final Plugin plugin;
    private final Set<String> shapeNames;

    LineDescriptor(Plugin plugin, Set<String> shapeNames) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.shapeNames = Set.copyOf(Objects.requireNonNull(shapeNames, "shapeNames"));
    }

    @Override
    public @NotNull String label(@NotNull SequenceLine line) {
        String token = line.token().isEmpty() ? "UNKNOWN" : line.token().replace('_', ' ');
        if (line.head().isBlank()) {
            return "{primary}&l" + token;
        }
        return "{primary}&l" + token + " {letters_black}» {letters}"
                + line.head().replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    @Override
    public @NotNull String icon(@NotNull SequenceLine line) {
        return SequenceLine.icon(line.token());
    }

    @Override
    public @NotNull List<String> lore(@NotNull SequenceLine line) {
        SequenceLine.Spec spec = spec(line);
        List<String> lore = new ArrayList<>(8);
        if (spec.isFree()) {
            lore.add("{secondary}Says:");
            lore.add(" {letters_black}▎ {letters}" + (line.rest().isBlank()
                    ? "{muted}nothing yet" : line.rest()));
            return lore;
        }
        List<String> settings = settings(line, spec);
        if (settings.isEmpty()) {
            lore.add("{secondary}Settings:");
            lore.add(" {letters_black}▎ {muted}all left at their defaults");
            return lore;
        }
        lore.add("{secondary}Settings:");
        // Four, then a count. A row is a row, not a config file: an admin who
        // needs the sixteenth parameter is already opening the form.
        for (int index = 0; index < Math.min(4, settings.size()); index++) {
            lore.add(settings.get(index));
        }
        if (settings.size() > 4) {
            lore.add(" {letters_black}▎ {muted}and " + (settings.size() - 4) + " more");
        }
        return lore;
    }

    private List<String> settings(SequenceLine line, SequenceLine.Spec spec) {
        List<String> settings = new ArrayList<>();
        if (spec.form() == SequenceLine.Form.POSITIONAL) {
            List<SequenceLine.Field> fields = spec.fields();
            for (int index = 0; index < fields.size(); index++) {
                String written = line.segment(index);
                if (!written.isBlank()) {
                    settings.add(setting(fields.get(index).label(), written));
                }
            }
            return settings;
        }
        for (SequenceLine.Field field : spec.fields()) {
            String written = line.value(field.key());
            if (!written.isBlank()) {
                settings.add(setting(field.label(), written));
            }
        }
        return settings;
    }

    private static String setting(String label, String value) {
        return " {letters_black}▎ {letters}" + label + " {letters_black}» {info}" + value;
    }

    @Override
    public @NotNull SequenceLine create() {
        // Never reached: the editor asks create(Player) first, and a line with no
        // token is not a line. Kept honest rather than returning a blank row.
        throw new IllegalStateException("a sequence line is always chosen, never created blank");
    }

    /**
     * Asks what the line plays, then which one.
     *
     * <p>The editor opens the form on the answer straight afterwards, so the
     * viewer sees three screens in a row — what, which, and its settings — and
     * backing out of any of them adds nothing.
     */
    @Override
    public @NotNull CompletionStage<Optional<SequenceLine>> create(@NotNull Player viewer) {
        return token(viewer).thenCompose(token -> {
            if (token.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<SequenceLine>empty());
            }
            SequenceLine.Spec spec = SequenceLine.spec(token.get(), shapeNames);
            return head(viewer, spec).thenApply(head -> {
                if (spec.head() != SequenceLine.Head.NONE && head.isEmpty()) {
                    return Optional.<SequenceLine>empty();
                }
                return Optional.of(SequenceLine.of(
                        SequenceLine.write(spec.token(), head.orElse(""), Map.of())));
            });
        });
    }

    private CompletionStage<Optional<String>> token(Player viewer) {
        List<String> tokens = SequenceLine.tokens(shapeNames);
        return Inputs.of(plugin).search(viewer, "{primary}&lWHAT DOES IT PLAY?", tokens)
                .label(token -> "{primary}&l" + token.replace('_', ' '))
                .key(token -> token)
                .icon(token -> material(SequenceLine.icon(token)))
                .open()
                .thenApply(result -> result.completed()
                        ? Optional.of(result.value()) : Optional.empty());
    }

    /** Which particle, sound, effect or block — whichever this token names. */
    private CompletionStage<Optional<String>> head(Player viewer, SequenceLine.Spec spec) {
        Pickers pickers = Editors.of(plugin).pick();
        return switch (spec.head()) {
            case PARTICLE -> pickers.particle(viewer);
            case SOUND -> pickers.sound(viewer);
            case POTION -> pickers.potionEffect(viewer);
            case MATERIAL -> pickers.material(viewer);
            case NONE -> CompletableFuture.completedFuture(Optional.<String>empty());
        };
    }

    @Override
    public @NotNull SequenceLine copy(@NotNull SequenceLine line) {
        // A new object, not the same one: two rows that are the same instance are
        // one row the editor cannot tell apart, and deleting either deletes both.
        return SequenceLine.of(line.text());
    }

    @Override
    public @NotNull String typeKey() {
        return SequenceLine.TYPE_KEY;
    }

    @Override
    public boolean isComplete(@NotNull SequenceLine line) {
        return line.isPlayable();
    }

    /**
     * One form, holding the fields this token actually reads.
     *
     * <p>Every value is prefilled with what the line already says and blank
     * means "leave it alone", so a form answered without touching anything
     * writes the same line back.
     */
    @Override
    public @NotNull CompletionStage<Optional<SequenceLine>> edit(@NotNull Player viewer,
                                                                 @NotNull SequenceLine line) {
        SequenceLine.Spec spec = spec(line);
        EditorForm form = EditorForm.of(plugin, viewer,
                "{primary}&l" + (spec.token().isEmpty() ? "LINE" : spec.token().replace('_', ' ')));

        if (spec.isFree()) {
            SequenceLine.Field only = spec.fields().get(0);
            // A line with no token at all is edited whole, and written back
            // whole: bracketing what somebody typed by hand would turn a line
            // they can still fix into "[] " and their text.
            boolean untokenised = spec.token().isEmpty();
            form.text(key(only), untokenised ? "The line, as it is written" : only.label(),
                    untokenised ? line.text() : line.rest(), PROSE_BOX).hint(only.hint());
            return form.ask(values -> SequenceLine.of(untokenised
                    ? text(values, only)
                    : SequenceLine.writeFree(spec.token(), text(values, only))));
        }

        if (spec.head() != SequenceLine.Head.NONE) {
            form.text(HEAD, headLabel(spec.head()), line.head())
                    .hint("Exactly as it is spelled in game, such as "
                            + example(spec.head()) + '.');
        }
        for (int index = 0; index < spec.fields().size(); index++) {
            SequenceLine.Field field = spec.fields().get(index);
            String current = spec.form() == SequenceLine.Form.POSITIONAL
                    ? line.segment(index)
                    : line.value(field.key());
            form.text(key(field), field.label(), current);
            if (field.hint() != null) {
                form.hint(field.hint());
            }
        }

        if (spec.form() == SequenceLine.Form.POSITIONAL) {
            return form.ask(values -> SequenceLine.of(
                    SequenceLine.writePositional(spec.token(), answers(values, spec))));
        }
        return form.ask(values -> SequenceLine.of(SequenceLine.write(spec.token(),
                head(values, spec, line), named(values, spec))));
    }

    /**
     * What the line names, after the form.
     *
     * <p>A blank parameter means "leave it at its default", but the head has no
     * default: a {@code [PARTICLE]} with nothing after it plays nothing and is
     * reported as a broken line. Emptying the field keeps what was there, which
     * is the only reading that cannot silently break a working effect.
     */
    private static String head(FormValues values, SequenceLine.Spec spec, SequenceLine line) {
        if (spec.head() == SequenceLine.Head.NONE) {
            return "";
        }
        String answered = text(values, HEAD);
        return answered.isBlank() ? line.head() : answered;
    }

    private List<String> answers(FormValues values, SequenceLine.Spec spec) {
        List<String> answers = new ArrayList<>(spec.fields().size());
        for (SequenceLine.Field field : spec.fields()) {
            // Semicolons separate the parts, so one typed into a title would make
            // two of them. Dropped rather than escaped: there is nothing to
            // escape it with, and a title is prose.
            answers.add(text(values, field).replace(';', ' '));
        }
        return answers;
    }

    private Map<String, String> named(FormValues values, SequenceLine.Spec spec) {
        Map<String, String> named = new LinkedHashMap<>();
        for (SequenceLine.Field field : spec.fields()) {
            named.put(field.key(), text(values, field).replace(';', ' '));
        }
        return named;
    }

    private SequenceLine.Spec spec(SequenceLine line) {
        return SequenceLine.spec(line.token(), shapeNames);
    }

    private static FormKey<String> key(SequenceLine.Field field) {
        return FormKey.text(field.key());
    }

    private static String text(FormValues values, SequenceLine.Field field) {
        return text(values, key(field));
    }

    /** An unanswered optional field is blank, not missing. */
    private static String text(FormValues values, FormKey<String> key) {
        return values.getOr(key, "").trim();
    }

    private static String headLabel(SequenceLine.Head head) {
        return switch (head) {
            case PARTICLE -> "Which particle";
            case SOUND -> "Which sound";
            case POTION -> "Which effect";
            case MATERIAL -> "Which block";
            case NONE -> "Which";
        };
    }

    private static String example(SequenceLine.Head head) {
        return switch (head) {
            case PARTICLE -> "FLAME";
            case SOUND -> "ENTITY_PLAYER_LEVELUP";
            case POTION -> "SPEED";
            case MATERIAL -> "STONE";
            case NONE -> "FLAME";
        };
    }

    private static Material material(String name) {
        Material material = Material.matchMaterial(name);
        return material != null ? material : Material.BLAZE_POWDER;
    }
}
