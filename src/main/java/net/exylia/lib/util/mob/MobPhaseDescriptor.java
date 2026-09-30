package net.exylia.lib.util.mob;

import net.exylia.lib.text.Phrases;
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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * How a fight phase draws and edits itself on screen, for the phases list of
 * {@link PluginMobs#fightEditor}.
 *
 * @since 1.198.0
 */
final class MobPhaseDescriptor implements EditorDescriptor<MobPhase> {

    private static final FormKey<BigDecimal> BELOW = FormKey.decimal("below");
    private static final FormKey<String> STYLE = FormKey.text("style");
    private static final FormKey<String> SUFFIX = FormKey.text("suffix");
    private static final FormKey<BigDecimal> SPEED = FormKey.decimal("speed");
    private static final FormKey<BigDecimal> DAMAGE = FormKey.decimal("damage");
    private static final FormKey<BigDecimal> RESIST = FormKey.decimal("resist");

    private final Plugin plugin;

    MobPhaseDescriptor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String label(@NotNull MobPhase phase) {
        return Phrases.tr("{primary}&lBELOW {0}%", number(phase.below() * 100));
    }

    @Override
    public @NotNull String icon(@NotNull MobPhase phase) {
        return "BLAZE_POWDER";
    }

    @Override
    public @NotNull List<String> lore(@NotNull MobPhase phase) {
        List<String> lore = new ArrayList<>(8);
        lore.add(Phrases.tr("{secondary}Phase:"));
        lore.add(line(Phrases.tr("Starts below"), Phrases.tr("{0}% health", number(phase.below() * 100))));
        if (!phase.suffix().isBlank()) {
            lore.add(Phrases.tr(" {letters_black}▎ {letters}Name gains {letters_black}» {0}", phase.suffix()));
        }
        lore.add(line(Phrases.tr("Style"), phase.style().isEmpty() ? Phrases.tr("enrage {letters_black}(auto)") : phase.style()));
        lore.add(line(Phrases.tr("Speed"), "×" + number(phase.speed())));
        lore.add(line(Phrases.tr("Damage"), "×" + number(phase.damage())));
        lore.add(line(Phrases.tr("Resists"), "×" + number(phase.resist())));

        return lore;
    }

    @Override
    public @NotNull MobPhase create() {
        return new MobPhase(0.5, "", "", 1, 1, 1);
    }

    @Override
    public @NotNull MobPhase copy(@NotNull MobPhase phase) {
        return new MobPhase(phase.below(), phase.style(), phase.suffix(), phase.speed(), phase.damage(), phase.resist());
    }

    @Override
    public @NotNull String typeKey() {
        return "exylia:mob-phases";
    }

    /** A phase at 0% or 100% never starts. */
    @Override
    public boolean isComplete(@NotNull MobPhase phase) {
        return phase.below() > 0 && phase.below() < 1;
    }

    @Override
    public @NotNull CompletionStage<Optional<MobPhase>> edit(@NotNull Player viewer, @NotNull MobPhase phase) {
        return EditorForm.of(plugin, viewer, Phrases.tr("{primary}&lPHASE"))
                .decimal(BELOW, Phrases.tr("Starts below this much health, in percent"), MobSkillDescriptor.decimal(phase.below() * 100))
                .hint(Phrases.tr("Hits left in hits mode. Between 0 and 100."))
                .field(SUFFIX, PluginMobs.optionalText(SUFFIX, Phrases.tr("Name gains"), phase.suffix()))
                .hint(Phrases.tr("Added to its name while in this phase, such as &c⚡. NONE for none."))
                .choice(STYLE, Phrases.tr("Style"), phase.style().isBlank() ? DEFAULT_STYLE : phase.style().toLowerCase(java.util.Locale.ROOT),
                        MobSkillDescriptor.options(styles(), phase.style().toLowerCase(java.util.Locale.ROOT)))
                .hint(Phrases.tr("How the change looks, the mob held still 1.2s for it. Default is enrage; none also skips the hold."))
                .decimal(SPEED, Phrases.tr("Speed, times its own"), MobSkillDescriptor.decimal(phase.speed()))
                .decimal(DAMAGE, Phrases.tr("Damage, times its own"), MobSkillDescriptor.decimal(phase.damage()))
                .decimal(RESIST, Phrases.tr("Resistance"), MobSkillDescriptor.decimal(phase.resist()))
                .hint(Phrases.tr("The damage it takes is divided by this. 1 changes nothing; 0.1 to 10."))
                .ask(values -> rebuild(phase, values));
    }

    static MobPhase rebuild(MobPhase phase, FormValues values) {
        String suffix = values.getOr(SUFFIX, "");
        return new MobPhase(read(values, BELOW, phase.below() * 100) / 100,
                style(values.getOr(STYLE, "")),
                suffix.trim().equalsIgnoreCase("NONE") ? "" : suffix,
                read(values, SPEED, phase.speed()),
                read(values, DAMAGE, phase.damage()),
                read(values, RESIST, phase.resist()));
    }

    /** The phase styles offered: the default, none, then every skill style. */
    private static final String DEFAULT_STYLE = "default";

    private static java.util.List<String> styles() {
        java.util.List<String> styles = new java.util.ArrayList<>(List.of(DEFAULT_STYLE));
        styles.add(MobSkills.NO_STYLE);
        styles.addAll(MobSkills.STYLES);
        return styles;
    }

    private static String style(String picked) {
        String style = picked.trim();
        return style.equalsIgnoreCase(DEFAULT_STYLE) ? "" : style;
    }

    private static double read(FormValues values, FormKey<BigDecimal> key, double current) {
        return values.has(key) ? values.get(key).doubleValue() : current;
    }

    private static String line(String label, String value) {
        return " {letters_black}▎ {letters}" + label + " {letters_black}» {info}" + value;
    }

    private static String number(double value) {
        return MobSkillDescriptor.decimal(value).toPlainString();
    }
}
