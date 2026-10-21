package net.exylia.lib.util.mob;

import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * How a model mob's own attack draws and edits itself on screen, for
 * {@link PluginMobs#attacksEditor}.
 *
 * @since 1.327.0
 */
final class MobAttackDescriptor implements EditorDescriptor<MobAttack> {

    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<String> PROJECTILE = FormKey.text("projectile");
    private static final FormKey<BigDecimal> REACH = FormKey.decimal("reach");
    private static final FormKey<BigDecimal> ARC = FormKey.decimal("arc");
    private static final FormKey<BigDecimal> DAMAGE = FormKey.decimal("damage");
    private static final FormKey<Duration> COOLDOWN = FormKey.duration("cooldown");
    private static final FormKey<BigDecimal> KNOCKBACK = FormKey.decimal("knockback");
    private static final FormKey<String> ANIMATION = FormKey.text("animation");
    private static final FormKey<Duration> WINDUP = FormKey.duration("windup");

    /** A blow, then what can be thrown. */
    static final List<String> PROJECTILES = List.of("NONE", "ARROW", "SNOWBALL", "SMALL_FIREBALL", "FIREBALL",
            "WITHER_SKULL");

    private final Plugin plugin;

    MobAttackDescriptor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String label(@NotNull MobAttack attack) {
        return "{primary}&l" + attack.name().toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public @NotNull String icon(@NotNull MobAttack attack) {
        return switch (attack.projectile()) {
            case "" -> "IRON_SWORD";
            case "ARROW" -> "BOW";
            case "SNOWBALL" -> "SNOWBALL";
            case "WITHER_SKULL" -> "WITHER_SKELETON_SKULL";
            default -> "FIRE_CHARGE";
        };
    }

    @Override
    public @NotNull List<String> lore(@NotNull MobAttack attack) {
        List<String> lore = new ArrayList<>(10);
        lore.add(Phrases.tr("{secondary}Attack:"));
        lore.add(line(Phrases.tr("Kind"), attack.ranged()
                ? Phrases.tr("throws {0}", readable(attack.projectile())) : Phrases.tr("a blow")));
        lore.add(line(attack.ranged() ? Phrases.tr("Range") : Phrases.tr("Reach"),
                Phrases.tr("{0} blocks", number(attack.reach()))));
        if (!attack.ranged()) lore.add(line(Phrases.tr("Arc"), number(attack.arc()) + "°"));
        lore.add(line(Phrases.tr("Damage"), attack.damage() > 0 ? number(attack.damage())
                : Phrases.tr("its attack damage")));
        lore.add(line(Phrases.tr("Cooldown"), number(attack.cooldown().toMillis() / 1000.0) + "s ⌚"));
        if (attack.knockback() > 0) lore.add(line(Phrases.tr("Knockback"), number(attack.knockback())));
        lore.add(line(Phrases.tr("Animation"), attack.state()));
        lore.add(line(Phrases.tr("Lands"), attack.windup().isZero() ? Phrases.tr("on its animation's beat")
                : Phrases.tr("after {0}s", number(attack.windup().toMillis() / 1000.0))));
        return lore;
    }

    @Override
    public @NotNull MobAttack create() {
        return MobAttack.DEFAULT;
    }

    @Override
    public @NotNull MobAttack copy(@NotNull MobAttack attack) {
        return attack.withName(attack.name());
    }

    @Override
    public @NotNull String typeKey() {
        return "exylia:mob-attacks";
    }

    @Override
    public @NotNull CompletionStage<Optional<MobAttack>> edit(@NotNull Player viewer, @NotNull MobAttack attack) {
        String thrown = attack.ranged() ? attack.projectile() : "NONE";
        return EditorForm.of(plugin, viewer, Phrases.tr("{primary}&lATTACK"))
                .text(NAME, Phrases.tr("Name"), attack.name())
                .hint(Phrases.tr("It plays attack_<name> from its model, else attack."))
                .choice(PROJECTILE, Phrases.tr("Throws"), thrown, MobSkillDescriptor.options(PROJECTILES, thrown))
                .hint(Phrases.tr("NONE strikes with its body."))
                .decimal(REACH, Phrases.tr("Reach, or range, in blocks"), MobSkillDescriptor.decimal(attack.reach()))
                .hint(Phrases.tr("A blow: how far past its body. A throw: how far it shoots."))
                .decimal(ARC, Phrases.tr("Arc, in degrees"), MobSkillDescriptor.decimal(attack.arc()))
                .hint(Phrases.tr("How wide in front of it a blow reaches. 360 hits all round."))
                .decimal(DAMAGE, Phrases.tr("Damage"), MobSkillDescriptor.decimal(attack.damage()))
                .hint(Phrases.tr("0 deals its attack damage attribute."))
                .field(COOLDOWN, net.exylia.lib.input.FormField.duration(COOLDOWN, Phrases.tr("Cooldown"))
                        .defaultValue(attack.cooldown()))
                .decimal(KNOCKBACK, Phrases.tr("Extra knockback"), MobSkillDescriptor.decimal(attack.knockback()))
                .hint(Phrases.tr("On top of the vanilla push. 0 for none."))
                .field(ANIMATION, PluginMobs.optionalText(ANIMATION, Phrases.tr("Animation"), attack.animation()))
                .hint(Phrases.tr("Blank plays the one its name gives."))
                .field(WINDUP, net.exylia.lib.input.FormField.duration(WINDUP, Phrases.tr("Lands after"))
                        .defaultValue(attack.windup().isZero() ? null : attack.windup()).optional())
                .hint(Phrases.tr("Blank lands on its animation's release or impact key."))
                .ask(values -> rebuild(attack, values));
    }

    static MobAttack rebuild(MobAttack attack, FormValues values) {
        String projectile = values.getOr(PROJECTILE, "NONE");
        return new MobAttack(values.getOr(NAME, attack.name()),
                read(values, REACH, attack.reach()),
                read(values, ARC, attack.arc()),
                read(values, DAMAGE, attack.damage()),
                values.getOr(COOLDOWN, attack.cooldown()),
                read(values, KNOCKBACK, attack.knockback()),
                projectile.equalsIgnoreCase("NONE") ? "" : projectile,
                values.getOr(ANIMATION, "").trim(),
                values.getOr(WINDUP, Duration.ZERO));
    }

    private static double read(FormValues values, FormKey<BigDecimal> key, double current) {
        return values.has(key) ? values.get(key).doubleValue() : current;
    }

    private static String line(String label, String value) {
        return " {letters_black}▎ {letters}" + label + " {letters_black}» {info}" + value;
    }

    private static String readable(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private static String number(double value) {
        return MobSkillDescriptor.decimal(value).toPlainString();
    }
}
