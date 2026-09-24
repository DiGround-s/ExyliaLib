package net.exylia.lib.util.mob;

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
        return "{primary}&lBELOW " + number(phase.below() * 100) + "%";
    }

    @Override
    public @NotNull String icon(@NotNull MobPhase phase) {
        return "BLAZE_POWDER";
    }

    @Override
    public @NotNull List<String> lore(@NotNull MobPhase phase) {
        List<String> lore = new ArrayList<>(8);
        lore.add("{secondary}Phase:");
        lore.add(line("Starts below", number(phase.below() * 100) + "% health"));
        if (!phase.suffix().isBlank()) lore.add(" {letters_black}▎ {letters}Name gains {letters_black}» " + phase.suffix());
        if (!phase.style().isEmpty()) lore.add(line("Style", phase.style()));
        lore.add(line("Speed", "×" + number(phase.speed())));
        lore.add(line("Damage", "×" + number(phase.damage())));
        lore.add(line("Resists", "×" + number(phase.resist())));
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
        return EditorForm.of(plugin, viewer, "{primary}&lPHASE")
                .decimal(BELOW, "Starts below this much health, in percent", MobSkillDescriptor.decimal(phase.below() * 100))
                .hint("Hits left in hits mode. Between 0 and 100.")
                .field(SUFFIX, PluginMobs.optionalText(SUFFIX, "Name gains", phase.suffix()))
                .hint("Added to its name while in this phase, such as &c⚡. NONE for none.")
                .field(STYLE, PluginMobs.optionalText(STYLE, "Style", phase.style()))
                .hint("How the change looks. Blank for the default.")
                .decimal(SPEED, "Speed, times its own", MobSkillDescriptor.decimal(phase.speed()))
                .decimal(DAMAGE, "Damage, times its own", MobSkillDescriptor.decimal(phase.damage()))
                .decimal(RESIST, "Resistance", MobSkillDescriptor.decimal(phase.resist()))
                .hint("The damage it takes is divided by this. 1 changes nothing; 0.1 to 10.")
                .ask(values -> rebuild(phase, values));
    }

    static MobPhase rebuild(MobPhase phase, FormValues values) {
        String suffix = values.getOr(SUFFIX, "");
        return new MobPhase(read(values, BELOW, phase.below() * 100) / 100,
                values.getOr(STYLE, "").trim(),
                suffix.trim().equalsIgnoreCase("NONE") ? "" : suffix,
                read(values, SPEED, phase.speed()),
                read(values, DAMAGE, phase.damage()),
                read(values, RESIST, phase.resist()));
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
