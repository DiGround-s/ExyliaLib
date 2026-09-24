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
 * <h2>Editing one is a section at a time</h2>
 * A row click asks which part: {@link Section#MECHANICS} (what the type does),
 * {@link Section#TIMING} (wind-up, aim, rotation group, chain) or
 * {@link Section#CONDITIONS} (health, range, players nearby, phase). Each is one
 * prefilled form, so the common edit stays one dialog and a mob that only ever
 * leaps is never shown twenty fields.
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
    private static final FormKey<String> EFFECT = FormKey.text("effect");
    private static final FormKey<Duration> WINDUP = FormKey.duration("windup");
    private static final FormKey<String> AIM = FormKey.text("aim");
    private static final FormKey<BigDecimal> SPREAD = FormKey.decimal("spread");
    private static final FormKey<String> GROUP = FormKey.text("group");
    private static final FormKey<String> THEN = FormKey.text("then");
    private static final FormKey<String> NAME = FormKey.text("name");
    private static final FormKey<String> WINDUP_LINES = FormKey.text("windup_lines");
    private static final FormKey<BigDecimal> MIN_HEALTH = FormKey.decimal("min_health");
    private static final FormKey<BigDecimal> MAX_HEALTH = FormKey.decimal("max_health");
    private static final FormKey<BigDecimal> MIN_RANGE = FormKey.decimal("min_range");
    private static final FormKey<BigDecimal> MAX_RANGE = FormKey.decimal("max_range");
    private static final FormKey<BigDecimal> NEARBY = FormKey.decimal("nearby");
    private static final FormKey<Long> PHASE = FormKey.integer("phase");

    /**
     * The parts of a skill a row click offers, each one form.
     *
     * <p>The seam for more: a section is a constant here and a case in
     * {@link #edit(Player, MobSkill)}.
     */
    enum Section {
        MECHANICS("{primary}&lMECHANICS", Material.PISTON),
        TIMING("{primary}&lTIMING & AIM", Material.CLOCK),
        CONDITIONS("{primary}&lCONDITIONS", Material.COMPARATOR);

        final String label;
        final Material icon;

        Section(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }
    }

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
        return lore(skill, List.of(skill));
    }

    /** With the rest in view, so a grouped skill shows its share of its group. */
    @Override
    public @NotNull List<String> lore(@NotNull MobSkill skill, @NotNull List<MobSkill> siblings) {
        List<String> lore = new ArrayList<>(20);
        lore.add("{secondary}Skill:");
        lore.add(" {letters_black}▎ {letters}When {letters_black}» {info}" + when(skill));
        if (skill.grouped()) {
            double total = siblings.stream().filter(MobSkill::grouped)
                    .filter(other -> other.cast().group().equals(skill.cast().group()))
                    .mapToDouble(MobSkill::chance).sum();
            lore.add(line("Weight", number(skill.chance() * 100)
                    + (total > 0 ? " {letters_black}(" + "{info}" + percent(skill.chance() / total) + "%{letters_black})" : "")));
            if (!skill.cooldown().isZero()) {
                lore.add(" {letters_black}▎ {letters}Cooldown {letters_black}» {info}" + time(skill.cooldown()) + " ⌚");
            }
        } else {
            lore.add(" {letters_black}▎ {letters}Chance {letters_black}» {info}" + percent(skill.chance()) + "%");
            if (skill.trigger() != MobSkill.Trigger.INTERVAL && !skill.cooldown().isZero()) {
                lore.add(" {letters_black}▎ {letters}Cooldown {letters_black}» {info}" + time(skill.cooldown()) + " ⌚");
            }
        }
        List<String> settings = settings(skill);
        if (!skill.effect().isBlank()) settings.add(line("Looks like", firstLine(skill.effect())));
        if (!settings.isEmpty()) {
            lore.add("");
            lore.add("{secondary}Settings:");
            lore.addAll(settings);
        }
        List<String> cast = cast(skill.cast());
        if (!cast.isEmpty()) {
            lore.add("");
            lore.add("{secondary}Cast:");
            lore.addAll(cast);
        }
        return lore;
    }

    /** One line per part of the cast that is not its default. */
    static List<String> cast(MobSkill.Cast cast) {
        List<String> lines = new ArrayList<>(8);
        if (!cast.name().isEmpty()) lines.add(line("Name", cast.name()));
        if (!cast.windup().isZero()) lines.add(line("Wind-up", time(cast.windup()) + " ⌚"));
        if (cast.aim() != MobSkill.Aim.AUTO) {
            String spread = cast.spread() <= 0 ? "" : cast.aim() == MobSkill.Aim.CONE ? " " + number(cast.spread()) + "°"
                    : cast.aim() == MobSkill.Aim.LINE ? " " + number(cast.spread()) + " wide" : "";
            lines.add(line("Aim", cast.aim().readable() + spread));
        }
        MobSkill.Gate when = cast.when();
        if (when.minHealth() > 0 || when.maxHealth() < 1) {
            lines.add(line("Health", percent(when.minHealth()) + "-" + percent(when.maxHealth()) + "%"));
        }
        if (when.minRange() > 0 || when.maxRange() > 0) {
            lines.add(line("Target", number(when.minRange()) + "-"
                    + (when.maxRange() > 0 ? number(when.maxRange()) : "∞") + " blocks"));
        }
        if (when.nearby() > 0) lines.add(line("Needs a player within", number(when.nearby()) + " blocks"));
        if (when.phase() > 0) lines.add(line("Phase", String.valueOf(when.phase())));
        if (!cast.then().isEmpty()) lines.add(line("Then", "→ " + cast.then()));
        return lines;
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
            case TELEPORT -> {
                if (skill.radius() > 0) lines.add(line("Blinks up to", number(skill.radius()) + " blocks"));
            }
            case JUMP -> lines.add(line("Strength", number(skill.amount())));
            case SIZE -> lines.add(line("Scale", skill.text().isBlank() ? "{error}none" : skill.text()));
            case SPEED -> {
                lines.add(line("Speed", "×" + number(skill.amount())));
                lines.add(line("For", time(skill.duration()) + " ⌚"));
            }
            case BABY -> lines.add(line("For", time(skill.duration()) + " ⌚"));
        }
        return lines;
    }

    @Override
    public @NotNull MobSkill create() {
        return MobSkill.of(MobSkill.Type.LEAP, MobSkill.Trigger.INTERVAL);
    }

    /** Asks what it does, then when, then the form with only what that type reads. */
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
                            .thenCompose(trigger -> trigger.completed()
                                    ? mechanics(viewer, MobSkill.of(type.value(), trigger.value()))
                                    : CompletableFuture.completedFuture(Optional.<MobSkill>empty()));
                });
    }

    /** The form was part of creating it; opening it again would ask twice. */
    @Override
    public boolean editsNew() {
        return false;
    }

    @Override
    public @NotNull MobSkill copy(@NotNull MobSkill skill) {
        // A record: equal but its own row, and nothing in it is mutable.
        return new MobSkill(skill.trigger(), skill.type(), skill.chance(), skill.cooldown(),
                skill.threshold(), skill.radius(), skill.amount(), skill.duration(), skill.text(), skill.effect(),
                skill.cast());
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    /** A skill whose type needs a text and has none does nothing when it fires. */
    @Override
    public boolean isComplete(@NotNull MobSkill skill) {
        return switch (skill.type()) {
            case POTION, SUMMON, EFFECT, COMMAND, SIZE -> !skill.text().isBlank();
            default -> true;
        };
    }

    /** Asks which part, then opens that part's form. */
    @Override
    public @NotNull CompletionStage<Optional<MobSkill>> edit(@NotNull Player viewer, @NotNull MobSkill skill) {
        return Inputs.of(plugin)
                .choice(viewer, "{primary}&lEDIT " + skill.type().readable().toUpperCase(Locale.ROOT),
                        List.of(Section.values()))
                .label(section -> section.label)
                .icon(section -> section == Section.MECHANICS ? iconOf(skill.type()) : section.icon)
                .key(Enum::name)
                .open()
                .thenCompose(section -> {
                    if (!section.completed()) return CompletableFuture.completedFuture(Optional.<MobSkill>empty());
                    return switch (section.value()) {
                        case MECHANICS -> mechanics(viewer, skill);
                        case TIMING -> timing(viewer, skill);
                        case CONDITIONS -> conditions(viewer, skill);
                    };
                });
    }

    /** When it fires, its odds and what its type reads. */
    private CompletionStage<Optional<MobSkill>> mechanics(Player viewer, MobSkill skill) {
        boolean interval = skill.trigger() == MobSkill.Trigger.INTERVAL;
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lEDIT SKILL")
                .text(WHEN, "When", skill.trigger().name())
                .hint("SPAWN, INTERVAL, ATTACK, DAMAGED, LOW_HEALTH, DEATH or PHASE.")
                .decimal(CHANCE, skill.grouped() ? "Weight in its group" : "Chance, in percent",
                        decimal(skill.chance() * 100))
                .field(COOLDOWN, FormField.duration(COOLDOWN, interval ? "Every" : "Cooldown")
                        .defaultValue(skill.cooldown()).optional())
                .hint(skill.grouped() ? "Its group decides how often. This is the least time between two of its own casts; 0 for none."
                        : interval ? "How often it is tried. 1s at least." : "The shortest gap between two casts. 5s, 1m.");
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
            case TELEPORT -> form.decimal(RADIUS, "Radius, in blocks", decimal(skill.radius()))
                    .hint("0 appears behind the target. Above 0 blinks to a random spot on the ground that far.");
            case JUMP -> form.decimal(AMOUNT, "Strength", decimal(skill.amount()))
                    .hint("Upward speed. 0.8 is a hop, 1.5 a leap.");
            case SIZE -> form.text(TEXT, "Scale", skill.text())
                    .hint("min|max, such as 0.7|1.8, or one number. 0.1 at least.");
            case SPEED -> form.decimal(AMOUNT, "Speed, times its own", decimal(skill.amount()))
                    .field(DURATION, FormField.duration(DURATION, "For").defaultValue(skill.duration()));
            case BABY -> form.field(DURATION, FormField.duration(DURATION, "For").defaultValue(skill.duration()));
        }
        form.field(EFFECT, PluginMobs.optionalText(EFFECT, "Effect lines", skill.effect()).lines(4))
                .hint(skill.type() == MobSkill.Type.TELEPORT
                        ? "Played where it leaves and where it lands. One per line. NONE for none."
                        : "Played as it goes off. One per line, such as [SOUND] ENTITY_LLAMA_SPIT;1;1. NONE for none.");
        return form.ask(values -> rebuild(skill, values));
    }

    /** Wind-up, aim, rotation group, chain and the lines played as it winds up. */
    private CompletionStage<Optional<MobSkill>> timing(Player viewer, MobSkill skill) {
        MobSkill.Cast cast = skill.cast();
        EditorForm form = EditorForm.of(plugin, viewer, "{primary}&lTIMING & AIM")
                .field(WINDUP, FormField.duration(WINDUP, "Wind-up").defaultValue(zeroAsBlank(cast.windup())).optional())
                .hint("It stops, faces its aim and telegraphs this long before it lands. 0 lands at once. 800ms, 1.5s.")
                .text(AIM, "Aim", cast.aim().name())
                .hint("AUTO, TARGET, NEAREST, FARTHEST, RANDOM, ALL, CONE, LINE, SELF or GROUND. "
                        + "GROUND strikes where the target stood as the wind-up began.")
                .decimal(SPREAD, "Spread", decimal(cast.spread()))
                .hint("CONE: its angle in degrees, 60 when 0. LINE: its width in blocks, 1.6 when 0.");
        if (skill.trigger() == MobSkill.Trigger.INTERVAL) {
            form.field(GROUP, PluginMobs.optionalText(GROUP, "Rotation group", cast.group()))
                    .hint("Skills of one group take turns: one per period, picked by weight. NONE rolls on its own.");
        }
        form.field(NAME, PluginMobs.optionalText(NAME, "Name", cast.name()))
                .hint("What another skill's Then calls it by. NONE for none.")
                .field(THEN, PluginMobs.optionalText(THEN, "Then", cast.then()))
                .hint("The name of a skill cast right after this one lands. NONE for none.")
                .field(WINDUP_LINES, PluginMobs.optionalText(WINDUP_LINES, "Wind-up lines", cast.windupLines()).lines(4))
                .hint("Sequence lines played as the wind-up starts. One per line. NONE for none.");
        return form.ask(values -> timing(skill, values));
    }

    static MobSkill timing(MobSkill skill, FormValues values) {
        MobSkill.Cast cast = skill.cast();
        return skill.withCast(new MobSkill.Cast(cleared(values.getOr(NAME, "")), aim(values.getOr(AIM, ""), cast.aim()),
                values.getOr(WINDUP, Duration.ZERO), cast.style(), cast.tint(),
                read(values, SPREAD, cast.spread()), cast.when(),
                skill.trigger() == MobSkill.Trigger.INTERVAL ? cleared(values.getOr(GROUP, "")) : cast.group(),
                cleared(values.getOr(THEN, "")), cleared(values.getOr(WINDUP_LINES, ""))));
    }

    /** Health, range, players nearby and phase. */
    private CompletionStage<Optional<MobSkill>> conditions(Player viewer, MobSkill skill) {
        MobSkill.Gate when = skill.cast().when();
        return EditorForm.of(plugin, viewer, "{primary}&lCONDITIONS")
                .decimal(MIN_HEALTH, "From this much health, in percent", decimal(when.minHealth() * 100))
                .decimal(MAX_HEALTH, "Up to this much health, in percent", decimal(when.maxHealth() * 100))
                .hint("Hits left in hits mode. 0 and 100 for always.")
                .decimal(MIN_RANGE, "Target at least this far, in blocks", decimal(when.minRange()))
                .decimal(MAX_RANGE, "Target at most this far, in blocks", decimal(when.maxRange()))
                .hint("0 for no limit. With either set, a mob with no target does not cast it.")
                .decimal(NEARBY, "Needs a player within, in blocks", decimal(when.nearby()))
                .hint("A survival or adventure player. 0 needs nobody.")
                .integer(PHASE, "Only in phase", (long) when.phase())
                .hint("1 is the start; its fight's phases follow. 0 for any.")
                .ask(values -> conditions(skill, values));
    }

    static MobSkill conditions(MobSkill skill, FormValues values) {
        MobSkill.Gate when = skill.cast().when();
        return skill.withCast(skill.cast().withWhen(new MobSkill.Gate(
                unit(read(values, MIN_HEALTH, when.minHealth() * 100) / 100),
                unit(read(values, MAX_HEALTH, when.maxHealth() * 100) / 100),
                read(values, MIN_RANGE, when.minRange()),
                read(values, MAX_RANGE, when.maxRange()),
                read(values, NEARBY, when.nearby()),
                (int) Math.max(0, Math.min(Integer.MAX_VALUE, values.getOr(PHASE, (long) when.phase()))))));
    }

    /** An aim nobody can read keeps the one it had. */
    private static MobSkill.Aim aim(String written, MobSkill.Aim current) {
        try {
            return MobSkill.Aim.valueOf(written.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return current;
        }
    }

    /** Text as typed; {@code NONE} clears it. */
    private static String cleared(String typed) {
        String text = typed.trim();
        return text.equalsIgnoreCase("NONE") ? "" : text;
    }

    private static @org.jetbrains.annotations.Nullable Duration zeroAsBlank(Duration duration) {
        return duration.isZero() ? null : duration;
    }

    /** The answers back into a skill; a field the form did not ask keeps its value. */
    static MobSkill rebuild(MobSkill skill, FormValues values) {
        MobSkill.Trigger trigger = trigger(values.getOr(WHEN, ""), skill.trigger());
        return new MobSkill(trigger, skill.type(),
                unit(read(values, CHANCE, skill.chance() * 100) / 100),
                values.getOr(COOLDOWN, Duration.ZERO),
                unit(read(values, THRESHOLD, skill.threshold() * 100) / 100),
                read(values, RADIUS, skill.radius()),
                read(values, AMOUNT, skill.amount()),
                values.getOr(DURATION, skill.duration()),
                values.has(TEXT) ? values.get(TEXT).trim() : (touchesText(skill.type()) ? "" : skill.text()),
                effectLines(values.getOr(EFFECT, "")),
                skill.cast());
    }

    /** Effect lines as typed; {@code NONE} clears them, since a blank box keeps the old ones. */
    private static String effectLines(String typed) {
        String lines = typed.trim();
        return lines.equalsIgnoreCase("NONE") ? "" : lines;
    }

    /** A share typed as a percent, kept within {@code 0-1} whatever was typed. */
    private static double unit(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }

    private static boolean touchesText(MobSkill.Type type) {
        return switch (type) {
            case POTION, SUMMON, PROJECTILE, EFFECT, COMMAND, SIZE -> true;
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

    /**
     * A value as the form shows it: two decimals at most, never in scientific
     * notation ({@code 100} stripped of its zeros is {@code 1E+2}).
     */
    static BigDecimal decimal(double value) {
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
    }

    private static String when(MobSkill skill) {
        if (skill.grouped()) return "Takes turns in " + skill.cast().group();
        return switch (skill.trigger()) {
            case SPAWN -> "On spawn";
            case INTERVAL -> "Every " + time(skill.period()) + " ⌚";
            case ATTACK -> "When it hits";
            case DAMAGED -> "When it is hit";
            case LOW_HEALTH -> "At " + percent(skill.threshold()) + "% health";
            case DEATH -> "On death";
            case PHASE -> skill.cast().when().phase() > 0 ? "Entering phase " + skill.cast().when().phase()
                    : "Entering each phase";
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
            case JUMP -> Material.SLIME_BALL;
            case SIZE -> Material.PUFFERFISH;
            case SPEED -> Material.SUGAR;
            case BABY -> Material.EGG;
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
            case PHASE -> Material.BLAZE_POWDER;
        };
    }
}
