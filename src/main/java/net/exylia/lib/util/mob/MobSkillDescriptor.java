package net.exylia.lib.util.mob;

import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.util.TimeFormats;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * How a mob skill draws and edits itself on screen.
 *
 * <p>Handed to the list editor by {@link PluginMobs#skillsEditor}.
 *
 * <h2>Creating one is two questions</h2>
 * What the skill does decides which fields its form has, and when it fires
 * decides whether it has a cooldown or a period, so both are asked first. The
 * form after that shows only what that type reads: a leap is never asked for a
 * command.
 *
 * @since 1.192.0
 */
final class MobSkillDescriptor implements EditorDescriptor<MobSkill> {

    /** The clipboard bucket mob skills share, whichever plugin copied them. */
    static final String TYPE_KEY = "exylia:mob-skills";

    private static final FormKey<String> WHEN = FormKey.text("when");
    private static final FormKey<BigDecimal> CHANCE = FormKey.decimal("chance");
    private static final FormKey<Duration> COOLDOWN = FormKey.duration("cooldown");
    private static final FormKey<BigDecimal> THRESHOLD = FormKey.decimal("threshold");
    private static final FormKey<BigDecimal> RADIUS = FormKey.decimal("radius");
    private static final FormKey<BigDecimal> AMOUNT = FormKey.decimal("amount");
    private static final FormKey<Duration> DURATION = FormKey.duration("duration");
    private static final FormKey<String> TEXT = FormKey.text("text");

    private final Plugin plugin;

    MobSkillDescriptor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String label(@NotNull MobSkill skill) {
        return "{primary}&l" + skill.type().readable().toUpperCase(Locale.ROOT);
    }

    @Override
    public @NotNull String icon(@NotNull MobSkill skill) {
        return iconOf(skill.type()).name();
    }

    @Override
    public @NotNull List<String> lore(@NotNull MobSkill skill) {
        List<String> lore = new ArrayList<>(12);
        lore.add("{secondary}Skill:");
        lore.add(" {letters_black}▎ {letters}When {letters_black}» {info}" + when(skill));
        lore.add(" {letters_black}▎ {letters}Chance {letters_black}» {info}" + percent(skill.chance()) + "%");
        if (skill.trigger() != MobSkill.Trigger.INTERVAL && !skill.cooldown().isZero()) {
            lore.add(" {letters_black}▎ {letters}Cooldown {letters_black}» {info}" + time(skill.cooldown()) + " ⌚");
        }
        List<String> settings = settings(skill);
        if (!settings.isEmpty()) {
            lore.add("");
            lore.add("{secondary}Settings:");
            lore.addAll(settings);
        }
        return lore;
    }

    /** One line per field the type reads, and only those. */
    private static List<String> settings(MobSkill skill) {
        List<String> lines = new ArrayList<>(3);
        switch (skill.type()) {
            case LEAP, PULL -> lines.add(line("Strength", number(skill.amount())));
            case PUSH -> {
                lines.add(line("Radius", number(skill.radius()) + " blocks"));
                lines.add(line("Strength", number(skill.amount())));
            }
            case POTION -> {
                lines.add(line("Effect", skill.text()));
                lines.add(line("Reaches", skill.radius() > 0 ? number(skill.radius()) + " blocks" : "the target"));
            }
            case SUMMON -> {
                lines.add(line("Template", skill.text().isBlank() ? "{error}none" : skill.text()));
                lines.add(line("Alive at once", number(Math.max(1, Math.round(skill.amount())))));
            }
            case LIGHTNING -> lines.add(line("Damage", skill.amount() > 0 ? number(skill.amount()) : "none"));
            case PROJECTILE -> {
                lines.add(line("Throws", skill.text().isBlank() ? "FIREBALL" : skill.text()));
                lines.add(line("Speed", number(skill.amount())));
            }
            case HEAL -> lines.add(line("Restores", number(skill.amount()) + "%"));
            case AREA_DAMAGE -> {
                lines.add(line("Radius", number(skill.radius()) + " blocks"));
                lines.add(line("Damage", number(skill.amount())));
            }
            case IGNITE -> lines.add(line("Burns", time(skill.duration()) + " ⌚"));
            case EFFECT, COMMAND -> {
                if (!skill.text().isBlank()) lines.add(line(skill.type() == MobSkill.Type.EFFECT ? "Plays" : "Runs",
                        firstLine(skill.text())));
            }
            case TELEPORT -> { }
        }
        return lines;
    }

    @Override
    public @NotNull MobSkill create() {
        return MobSkill.of(MobSkill.Type.LEAP, MobSkill.Trigger.INTERVAL);
    }

    /** Asks what it does, then when; the editor opens the form on the answer. */
    @Override
    public @NotNull CompletionStage<Optional<MobSkill>> create(@NotNull Player viewer) {
        return Inputs.of(plugin)
                .choice(viewer, "{primary}&lWHAT DOES IT DO?", List.of(MobSkill.Type.values()))
                .label(type -> "{primary}&l" + type.readable().toUpperCase(Locale.ROOT))
                .icon(MobSkillDescriptor::iconOf)
                .key(Enum::name)
                .open()
                .thenCompose(type -> {
                    if (!type.completed()) {
                        return CompletableFuture.completedFuture(Optional.<MobSkill>empty());
                    }
                    return Inputs.of(plugin)
                            .choice(viewer, "{primary}&lWHEN?", List.of(MobSkill.Trigger.values()))
                            .label(trigger -> "{primary}&l" + trigger.readable().toUpperCase(Locale.ROOT))
                            .icon(MobSkillDescriptor::iconOf)
                            .key(Enum::name)
                            .open()
                            .thenApply(trigger -> trigger.completed()
                                    ? Optional.of(MobSkill.of(type.value(), trigger.value()))
                                    : Optional.<MobSkill>empty());
                });
    }

    @Override
    public @NotNull MobSkill copy(@NotNull MobSkill skill) {
        // A record: equal but its own row, and nothing in it is mutable.
        return new MobSkill(skill.trigger(), skill.type(), skill.chance(), skill.cooldown(),
                skill.threshold(), skill.radius(), skill.amount(), skill.duration(), skill.text());
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    /** A skill whose type needs a text and has none does nothing when it fires. */
    @Override
    public boolean isComplete(@NotNull MobSkill skill) {
        return switch (skill.type()) {
            case POTION, SUMMON, EFFECT, COMMAND -> !skill.text().isBlank();
            default -> true;
        };
    }

    @Override
    public @NotNull CompletionStage<Optional<MobSkill>> edit(@NotNull Player viewer, @NotNull MobSkill skill) {
        boolean interval = skill.trigger() == MobSkill.Trigger.INTERVAL;
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lEDIT SKILL")
                .text(WHEN, "When", skill.trigger().name())
                .hint("SPAWN, INTERVAL, ATTACK, DAMAGED, LOW_HEALTH or DEATH.")
                .decimal(CHANCE, "Chance, in percent", decimal(skill.chance() * 100))
                .field(COOLDOWN, FormField.duration(COOLDOWN, interval ? "Every" : "Cooldown")
                        .defaultValue(skill.cooldown()).optional())
                .hint(interval ? "How often it is tried. 1s at least." : "The shortest gap between two casts. 5s, 1m.");
        if (skill.trigger() == MobSkill.Trigger.LOW_HEALTH) {
            form.decimal(THRESHOLD, "Fires at this much health, in percent", decimal(skill.threshold() * 100));
        }
        switch (skill.type()) {
            case LEAP, PULL -> form.decimal(AMOUNT, "Strength", decimal(skill.amount()));
            case PUSH -> form.decimal(RADIUS, "Radius, in blocks", decimal(skill.radius()))
                    .decimal(AMOUNT, "Strength", decimal(skill.amount()));
            case POTION -> form.text(TEXT, "Effect", skill.text())
                    .hint("NAME|LEVEL|SECONDS, such as SLOWNESS|2|5.")
                    .decimal(RADIUS, "Radius, in blocks", decimal(skill.radius()))
                    .hint("0 gives it to the target alone.");
            case SUMMON -> form.text(TEXT, "Template id", skill.text())
                    .hint("A template of this plugin. Minions never summon.")
                    .decimal(AMOUNT, "Alive at once", decimal(skill.amount()))
                    .hint("Ten at most.")
                    .decimal(RADIUS, "Spread, in blocks", decimal(skill.radius()));
            case LIGHTNING -> form.decimal(AMOUNT, "Damage", decimal(skill.amount()))
                    .hint("0 is only the flash.");
            case PROJECTILE -> form.text(TEXT, "Projectile", skill.text())
                    .hint("FIREBALL, SMALL_FIREBALL, WITHER_SKULL, ARROW or SNOWBALL.")
                    .decimal(AMOUNT, "Speed", decimal(skill.amount()));
            case HEAL -> form.decimal(AMOUNT, "Restores, in percent of max health", decimal(skill.amount()));
            case AREA_DAMAGE -> form.decimal(RADIUS, "Radius, in blocks", decimal(skill.radius()))
                    .decimal(AMOUNT, "Damage", decimal(skill.amount()));
            case IGNITE -> form.field(DURATION, FormField.duration(DURATION, "Burns for")
                    .defaultValue(skill.duration()));
            case EFFECT -> form.text(TEXT, "Sequence lines", skill.text(), 4)
                    .hint("One per line, such as [PARTICLE] FLAME;count:20");
            case COMMAND -> form.text(TEXT, "Command the console runs", skill.text(), 3)
                    .hint("%player% is the target, %mob% the template id. No leading slash.");
            case TELEPORT -> { }
        }
        return form.ask(values -> rebuild(skill, values));
    }

    /** The answers back into a skill; a field the form did not ask keeps its value. */
    private static MobSkill rebuild(MobSkill skill, FormValues values) {
        MobSkill.Trigger trigger = trigger(values.getOr(WHEN, ""), skill.trigger());
        return new MobSkill(trigger, skill.type(),
                read(values, CHANCE, skill.chance() * 100) / 100,
                values.getOr(COOLDOWN, Duration.ZERO),
                read(values, THRESHOLD, skill.threshold() * 100) / 100,
                read(values, RADIUS, skill.radius()),
                read(values, AMOUNT, skill.amount()),
                values.getOr(DURATION, skill.duration()),
                values.has(TEXT) ? values.get(TEXT).trim() : (touchesText(skill.type()) ? "" : skill.text()));
    }

    private static boolean touchesText(MobSkill.Type type) {
        return switch (type) {
            case POTION, SUMMON, PROJECTILE, EFFECT, COMMAND -> true;
            default -> false;
        };
    }

    /** A trigger nobody can read keeps the one it had rather than becoming another. */
    private static MobSkill.Trigger trigger(String written, MobSkill.Trigger current) {
        try {
            return MobSkill.Trigger.valueOf(written.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException unknown) {
            return current;
        }
    }

    private static double read(FormValues values, FormKey<BigDecimal> key, double current) {
        return values.has(key) ? values.get(key).doubleValue() : current;
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static String when(MobSkill skill) {
        return switch (skill.trigger()) {
            case SPAWN -> "On spawn";
            case INTERVAL -> "Every " + time(skill.period()) + " ⌚";
            case ATTACK -> "When it hits";
            case DAMAGED -> "When it is hit";
            case LOW_HEALTH -> "At " + percent(skill.threshold()) + "% health";
            case DEATH -> "On death";
        };
    }

    private static String line(String label, String value) {
        return " {letters_black}▎ {letters}" + label + " {letters_black}» {info}" + value;
    }

    private static String firstLine(String text) {
        String first = text.strip().split("\\R", 2)[0];
        return first.length() > 32 ? first.substring(0, 31) + "…" : first;
    }

    private static String percent(double fraction) {
        return number(fraction * 100);
    }

    private static String number(double value) {
        return decimal(value).toPlainString();
    }

    private static String time(Duration duration) {
        return TimeFormats.render(duration.toMillis() / 1000.0);
    }

    private static Material iconOf(MobSkill.Type type) {
        return switch (type) {
            case LEAP -> Material.RABBIT_FOOT;
            case PULL -> Material.FISHING_ROD;
            case PUSH -> Material.PISTON;
            case POTION -> Material.SPLASH_POTION;
            case SUMMON -> Material.ZOMBIE_HEAD;
            case LIGHTNING -> Material.LIGHTNING_ROD;
            case PROJECTILE -> Material.FIRE_CHARGE;
            case HEAL -> Material.GOLDEN_APPLE;
            case TELEPORT -> Material.ENDER_PEARL;
            case AREA_DAMAGE -> Material.TNT;
            case IGNITE -> Material.FLINT_AND_STEEL;
            case EFFECT -> Material.BLAZE_POWDER;
            case COMMAND -> Material.COMMAND_BLOCK;
        };
    }

    private static Material iconOf(MobSkill.Trigger trigger) {
        return switch (trigger) {
            case SPAWN -> Material.SPAWNER;
            case INTERVAL -> Material.CLOCK;
            case ATTACK -> Material.IRON_SWORD;
            case DAMAGED -> Material.SHIELD;
            case LOW_HEALTH -> Material.RED_DYE;
            case DEATH -> Material.SKELETON_SKULL;
        };
    }
}
