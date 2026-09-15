package net.exylia.lib.util;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.util.Effects.ParsedEffect;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.util.editor.Editors;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

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
 * <h2>Every field an effect has</h2>
 * A potion effect is not only a level and a duration: whether it draws its
 * swirls, whether it shows in the corner of the screen and whether it is the
 * faint beacon kind are three more, and they are the difference between a kit
 * buff a player can read and one that covers the screen. All three are
 * checkboxes on the same form.
 *
 * @since 1.56.0
 */
final class PotionEffectDescriptor implements EditorDescriptor<ParsedEffect> {

    /** The clipboard bucket potion effects share. */
    static final String TYPE_KEY = "exylia:potion-effects";

    private static final int TICKS_PER_SECOND = 20;

    private static final FormKey<Long> LEVEL = FormKey.integer("level");
    private static final FormKey<Long> SECONDS = FormKey.integer("seconds");
    private static final FormKey<Boolean> PARTICLES = FormKey.flag("particles");
    private static final FormKey<Boolean> ICON = FormKey.flag("icon");
    private static final FormKey<Boolean> AMBIENT = FormKey.flag("ambient");

    /**
     * One bottle per effect name, built once.
     *
     * <p>A row is redrawn after every click, and painting a bottle means
     * building an item and serialising it. The name is all the colour depends
     * on, so the answer is the same every time.
     */
    private static final Map<String, String> BOTTLES = new ConcurrentHashMap<>();

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
        return BOTTLES.computeIfAbsent(entry.name(), PotionEffectDescriptor::bottle);
    }

    @Override
    public @NotNull List<String> lore(@NotNull ParsedEffect entry) {
        return List.of("{secondary}Effect:",
                " {letters_black}▎ {letters}Level {letters_black}» {info}" + (entry.amplifier() + 1),
                " {letters_black}▎ {letters}Lasts {letters_black}» {info}" + duration(entry) + " ⌚",
                "",
                "{secondary}On screen:",
                " {letters_black}▎ {letters}Particles {letters_black}» " + shown(entry.particles())
                        + (entry.ambient() ? " {letters_black}(faint)" : ""),
                " {letters_black}▎ {letters}Icon {letters_black}» " + shown(entry.icon()));
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
        return new ParsedEffect(entry.name(), entry.amplifier(), entry.duration(),
                entry.ambient(), entry.particles(), entry.icon());
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
                .flag(PARTICLES, "Show the swirling particles", entry.particles())
                .flag(ICON, "Show the icon in the corner of the screen", entry.icon())
                .flag(AMBIENT, "Faint particles, the way a beacon gives them", entry.ambient())
                .hint("Only matters while the particles are shown.")
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
        return new ParsedEffect(entry.name(), amplifier, duration,
                values.getBoolean(AMBIENT), values.getBoolean(PARTICLES), values.getBoolean(ICON));
    }

    /**
     * A bottle the client paints in the colour of what it holds.
     *
     * <p>Every row drew as the same grey potion before, under vanilla's own
     * "No effects" line — fifteen identical bottles for fifteen different
     * effects. The effect lines are hidden because the row's own name and lore
     * already say all three.
     */
    private static String bottle(String name) {
        // The same resolver the effects are applied through, so a legacy name
        // an old config still writes paints the same bottle it applies.
        if (!(Effects.getResolver().resolve(name) instanceof PotionEffectType type)) {
            return "POTION";
        }
        try {
            ItemStack item = new ItemStack(Material.POTION);
            if (item.getItemMeta() instanceof PotionMeta meta) {
                meta.addCustomEffect(new PotionEffect(type, 1, 0), true);
                meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                item.setItemMeta(meta);
            }
            return "bytes:" + Base64.getEncoder().encodeToString(item.serializeAsBytes());
        } catch (RuntimeException | LinkageError unpaintable) {
            return "POTION";
        }
    }

    private static String shown(boolean on) {
        return on ? "{success}Shown" : "{letters_black}Hidden";
    }

    /** The same effect picker every other editor opens, one bottle colour per effect. */
    private CompletionStage<Optional<String>> choose(Player viewer) {
        return Editors.of(plugin).pick().potionEffect(viewer);
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
