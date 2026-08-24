package net.exylia.lib.util;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.util.Effects.ParsedEffect;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * How a potion effect draws and edits itself on screen.
 *
 * <p>Handed to the list editor by {@link Effects#editor}.
 *
 * <h2>Levels are shown the way a player reads them</h2>
 * The stored amplifier is Bukkit's, counting from zero, and every admin who has
 * ever typed {@code SPEED|2} means Speed II. The form asks for the level and
 * stores the amplifier, which is the one piece of arithmetic that made commons'
 * potion editor produce effects one level weaker than they were asked for.
 *
 * @since 1.56.0
 */
final class PotionEffectDescriptor implements EditorDescriptor<ParsedEffect> {

    /** The clipboard bucket potion effects share. */
    static final String TYPE_KEY = "exylia:potion-effects";

    private static final int TICKS_PER_SECOND = 20;

    private static final FormKey<Long> LEVEL = FormKey.integer("level");
    private static final FormKey<Long> SECONDS = FormKey.integer("seconds");

    private final Plugin plugin;

    PotionEffectDescriptor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String label(@NotNull ParsedEffect entry) {
        return "{primary}&l" + readable(entry.name()).toUpperCase(Locale.ROOT)
                + " {letters_black}" + roman(entry.amplifier() + 1);
    }

    @Override
    public @NotNull String icon(@NotNull ParsedEffect entry) {
        return "POTION";
    }

    @Override
    public @NotNull List<String> lore(@NotNull ParsedEffect entry) {
        return List.of("{secondary}Effect:",
                " {letters_black}▎ {letters}Level {letters_black}» {info}" + (entry.amplifier() + 1),
                " {letters_black}▎ {letters}Lasts {letters_black}» {info}" + duration(entry) + " ⏱️");
    }

    @Override
    public @NotNull ParsedEffect create() {
        return new ParsedEffect("SPEED", 0, 10 * TICKS_PER_SECOND);
    }

    /** Asks which effect first: the list is long and nobody spells it from memory. */
    @Override
    public @NotNull CompletionStage<Optional<ParsedEffect>> create(@NotNull Player viewer) {
        return choose(viewer)
                .thenApply(chosen -> chosen.map(name ->
                        new ParsedEffect(name, 0, 10 * TICKS_PER_SECOND)));
    }

    @Override
    public @NotNull ParsedEffect copy(@NotNull ParsedEffect entry) {
        return new ParsedEffect(entry.name(), entry.amplifier(), entry.duration());
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    @Override
    public @NotNull CompletionStage<Optional<ParsedEffect>> edit(@NotNull Player viewer,
                                                                 @NotNull ParsedEffect entry) {
        return EditorForm.of(plugin, viewer, "{primary}&lEDIT EFFECT")
                .integer(LEVEL, "Level, as a player reads it", entry.amplifier() + 1L)
                .integer(SECONDS, "Seconds (-1 never ends)", seconds(entry))
                .ask(values -> rebuild(entry, values));
    }

    private static ParsedEffect rebuild(ParsedEffect entry, FormValues values) {
        // Level one is amplifier zero. Storing what was typed would make every
        // effect one level stronger than the admin asked for.
        int amplifier = (int) Math.max(1, values.getLong(LEVEL)) - 1;
        long seconds = values.getLong(SECONDS);
        int duration = seconds < 0
                ? Effects.INFINITE
                : (int) Math.max(1, seconds) * TICKS_PER_SECOND;
        return new ParsedEffect(entry.name(), amplifier, duration);
    }

    /**
     * The searchable list of every effect the server has.
     *
     * <p>Read from the registry rather than from {@code values()}: potion effect
     * types stopped being an enum, and a data pack can add one.
     */
    @SuppressWarnings("deprecation")
    private CompletionStage<Optional<String>> choose(Player viewer) {
        List<PotionEffectType> types = new ArrayList<>();
        for (PotionEffectType type : Registry.EFFECT) {
            types.add(type);
        }
        if (types.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.of("SPEED"));
        }
        return Inputs.of(plugin).search(viewer, "{primary}&lWHICH EFFECT?", types)
                .label(type -> readable(keyOf(type)))
                .key(PotionEffectDescriptor::keyOf)
                .icon(type -> Material.POTION)
                .open()
                .thenApply(result -> result.completed()
                        ? Optional.of(keyOf(result.value()).toUpperCase(Locale.ROOT))
                        : Optional.empty());
    }

    /**
     * A type's name.
     *
     * <p>Asked of the registry rather than of the type: {@code getKey()} on
     * these is marked for removal, and the registry has always known.
     */
    private static String keyOf(PotionEffectType type) {
        NamespacedKey key = Registry.EFFECT.getKey(type);
        return key == null ? "unknown" : key.getKey();
    }

    private static long seconds(ParsedEffect entry) {
        return entry.duration() == Effects.INFINITE ? -1L : entry.duration() / TICKS_PER_SECOND;
    }

    private static String duration(ParsedEffect entry) {
        return entry.duration() == Effects.INFINITE
                ? "forever"
                : TimeFormats.render(entry.duration() / (double) TICKS_PER_SECOND);
    }

    private static String readable(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Levels read as I, II, III on an item, and that is what the tooltip shows. */
    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }
}
