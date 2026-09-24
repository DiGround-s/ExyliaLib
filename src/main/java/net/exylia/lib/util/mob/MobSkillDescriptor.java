package net.exylia.lib.util.mob;

import net.exylia.lib.input.FormField;
import net.exylia.lib.input.FormKey;
import net.exylia.lib.input.FormValues;
import net.exylia.lib.input.Inputs;
import net.exylia.lib.util.TimeFormats;
import net.exylia.lib.util.editor.EditorDescriptor;
import net.exylia.lib.util.editor.EditorForm;
import net.exylia.lib.task.Tasks;
import net.exylia.lib.util.sequence.Sequences;
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
    private static final FormKey<Long> JUMPS = FormKey.integer("jumps");
    private static final FormKey<BigDecimal> STRIKE = FormKey.decimal("strike");

    /**
     * The parts of a skill a row click offers, each one form.
     *
     * <p>The seam for more: a section is a constant here and a case in
     * {@link #edit(Player, MobSkill)}.
     */
    enum Section {
        MECHANICS("{primary}&lMECHANICS", Material.PISTON),
        TIMING("{primary}&lTIMING & AIM", Material.CLOCK),
        CONDITIONS("{primary}&lCONDITIONS", Material.COMPARATOR),
        LOOK("{primary}&lLOOK", Material.PAINTING),
        PREVIEW("{primary}&l▶ PREVIEW", Material.ENDER_EYE);

        final String label;
        final Material icon;

        Section(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private final Plugin plugin;
    private final @org.jetbrains.annotations.Nullable PluginMobs mobs;

    /** @param mobs what plays a preview; {@code null} offers none */
    MobSkillDescriptor(Plugin plugin, @org.jetbrains.annotations.Nullable PluginMobs mobs) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mobs = mobs;
    }

    MobSkillDescriptor(Plugin plugin) {
        this(plugin, null);
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
        cast.addAll(look(skill));
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

    /** The style it is drawn in, and its tint when it has one. */
    static List<String> look(MobSkill skill) {
        List<String> lines = new ArrayList<>(2);
        String style = MobSkills.styleOf(skill);
        if (!style.equals(MobSkills.NO_STYLE)) {
            lines.add(line("Style", style + (skill.cast().style().isEmpty() ? " {letters_black}(auto)" : "")));
        }
        if (!skill.cast().tint().isEmpty()) lines.add(line("Tint", skill.cast().tint()));
        return lines;
    }

    /** One line per field the type reads, and only those. */
    private static List<String> settings(MobSkill skill) {
        List<String> lines = new ArrayList<>(3);
        switch (skill.type()) {
            case LEAP -> {
                lines.add(line("Strength", number(skill.amount())));
                if (skill.radius() > 0) lines.add(line("Lands hard within", number(skill.radius()) + " blocks"));
            }
            case PULL -> lines.add(line("Strength", number(skill.amount())));
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
                if (!skill.duration().isZero()) lines.add(line("Burns", time(skill.duration()) + " ⌚"));
            }
            case DASH -> {
                lines.add(line("Reach", number(skill.radius() > 0 ? skill.radius() : 12) + " blocks"));
                lines.add(line("Damage", number(skill.amount())));
            }
            case CHAIN -> {
                lines.add(line("Damage", number(skill.amount())));
                lines.add(line("Jumps", skill.text().isBlank() ? "4" : skill.text()));
                lines.add(line("Jump range", number(skill.radius() > 0 ? skill.radius() : 6) + " blocks"));
            }
            case SHIELD -> {
                lines.add(line("Blocks", number(skill.amount()) + "%"));
                lines.add(line("For", time(skill.duration()) + " ⌚"));
            }
            case ZONE -> {
                lines.add(line("Radius", number(skill.radius() > 0 ? skill.radius() : 3) + " blocks"));
                lines.add(line("Damage", number(skill.amount()) + " a second"));
                if (!skill.text().isBlank()) lines.add(line("Effect", skill.text()));
                lines.add(line("Lasts", time(skill.duration()) + " ⌚"));
            }
            case BARRAGE -> {
                lines.add(line("Strikes", number(skill.amount() > 0 ? skill.amount() : 5)));
                lines.add(line("Scatter", number(skill.radius() > 0 ? skill.radius() : 6) + " blocks"));
                lines.add(line("Damage each", skill.text().isBlank() ? "4" : skill.text()));
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

    /** What the add screen offers: every preset, then CUSTOM. */
    private static final String CUSTOM = "custom";

    /**
     * Asks which skill from the library, or CUSTOM; then when; then the form,
     * prefilled from the preset. CUSTOM asks what it does first, as before.
     */
    @Override
    public @NotNull CompletionStage<Optional<MobSkill>> create(@NotNull Player viewer) {
        List<Object> options = new ArrayList<>(MobSkills.library());
        options.add(CUSTOM);
        return Inputs.of(plugin)
                .choice(viewer, "{primary}&lADD A SKILL", options)
                .label(option -> option instanceof MobSkills.Preset preset ? "{primary}&l" + preset.label()
                        : "{primary}&lCUSTOM")
                .description(option -> option instanceof MobSkills.Preset preset ? preset.blurb()
                        : "Pick what it does and build it from scratch.")
                .icon(option -> option instanceof MobSkills.Preset preset ? preset.icon() : Material.WRITABLE_BOOK)
                .key(option -> option instanceof MobSkills.Preset preset ? preset.id() : CUSTOM)
                .open()
                .thenCompose(picked -> {
                    if (!picked.completed()) return CompletableFuture.completedFuture(Optional.<MobSkill>empty());
                    if (!(picked.value() instanceof MobSkills.Preset preset)) return custom(viewer);
                    return Inputs.of(plugin)
                            .choice(viewer, "{primary}&lWHEN?", List.of(MobSkill.Trigger.values()))
                            .label(trigger -> "{primary}&l" + trigger.readable().toUpperCase(Locale.ROOT))
                            .icon(MobSkillDescriptor::iconOf)
                            .key(Enum::name)
                            .defaultValue(preset.skill().trigger())
                            .open()
                            .thenCompose(trigger -> trigger.completed()
                                    ? mechanics(viewer, preset.skill().withTrigger(trigger.value()))
                                    : CompletableFuture.completedFuture(Optional.<MobSkill>empty()));
                });
    }

    /** Asks what it does, then when, then the form with only what that type reads. */
    private CompletionStage<Optional<MobSkill>> custom(Player viewer) {
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
        List<Section> sections = new ArrayList<>(List.of(Section.values()));
        // Nothing to watch on a skill drawn in no style.
        if (mobs == null || MobSkills.styleOf(skill).equals(MobSkills.NO_STYLE)) sections.remove(Section.PREVIEW);
        return Inputs.of(plugin)
                .choice(viewer, "{primary}&lEDIT " + skill.type().readable().toUpperCase(Locale.ROOT), sections)
                .label(section -> section.label)
                .description(section -> switch (section) {
                    case MECHANICS -> "When it fires and what it does.";
                    case TIMING -> "Wind-up, aim, turns and what it chains into.";
                    case CONDITIONS -> "Health, range and players it needs.";
                    case LOOK -> "Its style, tint and your own lines.";
                    case PREVIEW -> "Watch it from a stand-in four blocks ahead.";
                })
                .icon(section -> section == Section.MECHANICS ? iconOf(skill.type()) : section.icon)
                .key(Enum::name)
                .open()
                .thenCompose(section -> {
                    if (!section.completed()) return CompletableFuture.completedFuture(Optional.<MobSkill>empty());
                    return switch (section.value()) {
                        case MECHANICS -> mechanics(viewer, skill);
                        case TIMING -> timing(viewer, skill);
                        case CONDITIONS -> conditions(viewer, skill);
                        case LOOK -> look(viewer, skill);
                        case PREVIEW -> preview(viewer, skill);
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
            case LEAP -> form.decimal(AMOUNT, "Strength", decimal(skill.amount()))
                    .decimal(RADIUS, "Lands hard within, in blocks", decimal(skill.radius()))
                    .hint("0 only leaps. Above 0, whoever is that close as it lands takes its attack damage.");
            case PULL -> form.decimal(AMOUNT, "Strength", decimal(skill.amount()));
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
                    .decimal(AMOUNT, "Damage", decimal(skill.amount()))
                    .field(DURATION, FormField.duration(DURATION, "Sets them alight for")
                            .defaultValue(zeroAsBlank(skill.duration())).optional())
                    .hint("0 for no fire. 3s.");
            case DASH -> form.decimal(RADIUS, "Reach, in blocks", decimal(skill.radius()))
                    .hint("12 when 0, 24 at most. It runs the way it faced as it wound up.")
                    .decimal(AMOUNT, "Damage", decimal(skill.amount()))
                    .hint("Once to each player it runs through, who is thrown aside.");
            case CHAIN -> form.decimal(AMOUNT, "Damage", decimal(skill.amount()))
                    .integer(JUMPS, "Jumps", (long) jumpsOf(skill))
                    .hint("Players it hits in all, the target first. 8 at most.")
                    .decimal(RADIUS, "Jump range, in blocks", decimal(skill.radius()))
                    .hint("How far it leaps from one player to the next. 6 when 0.");
            case SHIELD -> form.decimal(AMOUNT, "Damage it blocks, in percent", decimal(skill.amount()))
                    .hint("100 blocks every hit. In hits mode no hit counts while it lasts.")
                    .field(DURATION, FormField.duration(DURATION, "For").defaultValue(skill.duration()));
            case ZONE -> form.decimal(RADIUS, "Radius, in blocks", decimal(skill.radius()))
                    .decimal(AMOUNT, "Damage a second", decimal(skill.amount()))
                    .field(DURATION, FormField.duration(DURATION, "Lasts").defaultValue(skill.duration()))
                    .hint("30s at most. Two per mob at once.")
                    .field(TEXT, PluginMobs.optionalText(TEXT, "Effect on those inside", skill.text()))
                    .hint("NAME|LEVEL|SECONDS, such as POISON|1|3. NONE for none. Aim SELF makes it go with the mob.");
            case BARRAGE -> form.decimal(AMOUNT, "Strikes", decimal(skill.amount()))
                    .hint("16 at most, a fifth of a second apart.")
                    .decimal(RADIUS, "Scatter, in blocks", decimal(skill.radius()))
                    .hint("Around its target. 6 when 0.")
                    .decimal(STRIKE, "Damage per strike", decimal(strikeOf(skill)))
                    .hint("To whoever stands within a block and a half as it lands.");
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

    private static int jumpsOf(MobSkill skill) {
        try {
            return skill.text().isBlank() ? 4 : (int) Math.round(Double.parseDouble(skill.text().trim()));
        } catch (NumberFormatException notANumber) {
            return 4;
        }
    }

    private static double strikeOf(MobSkill skill) {
        try {
            return skill.text().isBlank() ? 4 : Double.parseDouble(skill.text().trim());
        } catch (NumberFormatException notANumber) {
            return 4;
        }
    }

    // ------------------------------------------------------------------- look

    /** The parts of a look, each changed on its own trip. */
    private enum LookPart { STYLE, TINT, WINDUP_LINES, IMPACT_LINES }

    /** Style, tint, and the owner's own wind-up and impact lines. */
    private CompletionStage<Optional<MobSkill>> look(Player viewer, MobSkill skill) {
        MobSkill.Cast cast = skill.cast();
        return Inputs.of(plugin)
                .choice(viewer, "{primary}&lLOOK", List.of(LookPart.values()))
                .label(part -> switch (part) {
                    case STYLE -> "{primary}&lSTYLE &8[{info}" + shownStyle(skill) + "&8]";
                    case TINT -> "{primary}&lTINT &8[{info}" + (cast.tint().isEmpty() ? "theme" : cast.tint()) + "&8]";
                    case WINDUP_LINES -> "{primary}&lWIND-UP LINES &8[{info}" + count(cast.windupLines()) + "&8]";
                    case IMPACT_LINES -> "{primary}&lIMPACT LINES &8[{info}" + count(skill.effect()) + "&8]";
                })
                .description(part -> switch (part) {
                    case STYLE -> "How it is drawn: its warning, its release and its impact.";
                    case TINT -> "The colour it is drawn in; the theme's by default.";
                    case WINDUP_LINES -> "Your own lines, played as the wind-up starts.";
                    case IMPACT_LINES -> "Your own lines, played as it lands. With any, AUTO draws no style.";
                })
                .icon(part -> switch (part) {
                    case STYLE -> Material.PAINTING;
                    case TINT -> Material.MAGENTA_DYE;
                    case WINDUP_LINES -> Material.CLOCK;
                    case IMPACT_LINES -> Material.FIREWORK_STAR;
                })
                .key(Enum::name)
                .open()
                .thenCompose(part -> {
                    if (!part.completed()) return CompletableFuture.completedFuture(Optional.<MobSkill>empty());
                    return switch (part.value()) {
                        case STYLE -> style(viewer, skill);
                        case TINT -> tint(viewer, skill);
                        case WINDUP_LINES -> Sequences.of(plugin)
                                .editLines(viewer, "{primary}&lWIND-UP LINES", cast.windupLines())
                                .thenApply(lines -> lines.map(text -> skill.withCast(cast.withWindupLines(text))));
                        case IMPACT_LINES -> Sequences.of(plugin)
                                .editLines(viewer, "{primary}&lIMPACT LINES", skill.effect())
                                .thenApply(lines -> lines.map(skill::withEffect));
                    };
                });
    }

    /** The style as a row reads it: its own, or what AUTO draws. */
    static String shownStyle(MobSkill skill) {
        if (!skill.cast().style().isEmpty()) return skill.cast().style();
        return "auto: " + MobSkills.styleOf(skill);
    }

    private static String count(String lines) {
        long count = lines.lines().filter(line -> !line.isBlank()).count();
        return count == 0 ? "none" : String.valueOf(count);
    }

    private static final String AUTO = "auto";

    private CompletionStage<Optional<MobSkill>> style(Player viewer, MobSkill skill) {
        List<String> options = new ArrayList<>();
        options.add(AUTO);
        options.add(MobSkills.NO_STYLE);
        options.addAll(MobSkills.STYLES);
        String current = skill.cast().style().isEmpty() ? AUTO : skill.cast().style().toLowerCase(Locale.ROOT);
        String auto = skill.effect().isBlank() ? MobSkills.autoStyle(skill) : "none: it has impact lines";
        return Inputs.of(plugin)
                .choice(viewer, "{primary}&lSTYLE", options)
                .label(option -> option.equals(AUTO) ? "{primary}&lAUTO &8[{info}" + auto + "&8]"
                        : "{primary}&l" + option.toUpperCase(Locale.ROOT))
                .description(MobSkillDescriptor::blurb)
                .icon(MobSkillDescriptor::styleIcon)
                .key(option -> option)
                .defaultValue(options.contains(current) ? current : AUTO)
                .open()
                .thenApply(picked -> picked.completed()
                        ? Optional.of(skill.withCast(skill.cast().withStyle(picked.value().equals(AUTO) ? ""
                        : picked.value())))
                        : Optional.<MobSkill>empty());
    }

    /** The palette tokens a tint offers, then THEME to clear it and CUSTOM for a hex. */
    private static final List<String> TINTS = List.of("theme", "{primary}", "{secondary}", "{accent}", "{highlight}",
            "{info}", "{success}", "{warning}", "{error}", "custom");

    private CompletionStage<Optional<MobSkill>> tint(Player viewer, MobSkill skill) {
        String current = skill.cast().tint();
        String ticked = current.isEmpty() ? "theme" : TINTS.contains(current) ? current : "custom";
        return Inputs.of(plugin)
                .choice(viewer, "{primary}&lTINT", TINTS)
                .label(option -> switch (option) {
                    case "theme" -> "{primary}&lTHEME";
                    case "custom" -> "{primary}&lCUSTOM &8[{info}#rrggbb&8]";
                    default -> option + "&l" + option.substring(1, option.length() - 1).toUpperCase(Locale.ROOT);
                })
                .icon(MobSkillDescriptor::tintIcon)
                .key(option -> option.replace("{", "").replace("}", ""))
                .defaultValue(ticked)
                .open()
                .thenCompose(picked -> {
                    if (!picked.completed()) return CompletableFuture.completedFuture(Optional.<MobSkill>empty());
                    return switch (picked.value()) {
                        case "theme" -> CompletableFuture.completedFuture(
                                Optional.of(skill.withCast(skill.cast().withTint(""))));
                        case "custom" -> Inputs.of(plugin).text(viewer, "{primary}Type a colour as #rrggbb")
                                .defaultValue(current.startsWith("#") ? current : null)
                                .validate(typed -> typed.trim().matches("#[0-9a-fA-F]{6}"),
                                        "Write it as #rrggbb, such as #8a51c4.")
                                .open()
                                .thenApply(typed -> typed.completed()
                                        ? Optional.of(skill.withCast(skill.cast()
                                        .withTint(typed.value().trim().toLowerCase(Locale.ROOT))))
                                        : Optional.<MobSkill>empty());
                        default -> CompletableFuture.completedFuture(
                                Optional.of(skill.withCast(skill.cast().withTint(picked.value()))));
                    };
                });
    }

    // ---------------------------------------------------------------- preview

    /**
     * Plays its style to the viewer, then brings the sections back: the screen
     * stays out of the way while it plays, and comes back on the same row.
     */
    private CompletionStage<Optional<MobSkill>> preview(Player viewer, MobSkill skill) {
        long millis = mobs == null ? 0L : mobs.preview(viewer, skill);
        CompletableFuture<Optional<MobSkill>> answer = new CompletableFuture<>();
        Tasks.of(plugin).runAtEntityLater(viewer, Math.max(10L, millis / 50 + 5), () ->
                edit(viewer, skill).whenComplete((edited, failure) ->
                        answer.complete(failure == null ? edited : Optional.empty())));
        return answer;
    }

    static String blurb(String style) {
        MobSkills.Preset preset = MobSkills.preset(style);
        if (preset != null) return preset.blurb();
        return switch (style) {
            case AUTO -> "The style that suits its type.";
            case MobSkills.NO_STYLE -> "Nothing drawn; only its own lines play.";
            case "burst" -> "A shockwave ring that throws people back.";
            case "hop" -> "A spring off the ground in a puff of dust.";
            case "inflate" -> "Swells up with a pop.";
            case "zoom" -> "Speed lines and a gust.";
            case "shrink" -> "Shrinks down with a pop.";
            case "puff" -> "A cloud in the colour of what it casts.";
            default -> null;
        };
    }

    static Material styleIcon(String style) {
        MobSkills.Preset preset = MobSkills.preset(style);
        if (preset != null) return preset.icon();
        return switch (style) {
            case AUTO -> Material.NETHER_STAR;
            case MobSkills.NO_STYLE -> Material.BARRIER;
            case "burst" -> Material.WIND_CHARGE;
            case "hop" -> Material.FEATHER;
            case "inflate" -> Material.PUFFERFISH;
            case "zoom" -> Material.SUGAR;
            case "shrink" -> Material.EGG;
            case "puff" -> Material.SPLASH_POTION;
            default -> Material.PAPER;
        };
    }

    private static Material tintIcon(String tint) {
        return switch (tint) {
            case "{primary}" -> Material.PURPLE_DYE;
            case "{secondary}" -> Material.MAGENTA_DYE;
            case "{accent}" -> Material.PINK_DYE;
            case "{highlight}" -> Material.YELLOW_DYE;
            case "{info}" -> Material.LIGHT_BLUE_DYE;
            case "{success}" -> Material.LIME_DYE;
            case "{warning}" -> Material.ORANGE_DYE;
            case "{error}" -> Material.RED_DYE;
            case "custom" -> Material.WHITE_DYE;
            default -> Material.NETHER_STAR;
        };
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
                text(skill, values),
                effectLines(values.getOr(EFFECT, "")),
                skill.cast());
    }

    /** The text a type keeps: typed, or a number its own field asked for. */
    private static String text(MobSkill skill, FormValues values) {
        if (values.has(JUMPS)) return String.valueOf(Math.clamp(values.get(JUMPS), 1, 8));
        if (values.has(STRIKE)) return decimal(Math.max(0, values.get(STRIKE).doubleValue())).toPlainString();
        if (skill.type() == MobSkill.Type.ZONE) return values.has(TEXT) ? cleared(values.get(TEXT)) : skill.text();
        return values.has(TEXT) ? values.get(TEXT).trim() : (touchesText(skill.type()) ? "" : skill.text());
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
            case DASH -> Material.IRON_HORSE_ARMOR;
            case CHAIN -> Material.PRISMARINE_CRYSTALS;
            case SHIELD -> Material.SHIELD;
            case ZONE -> Material.LINGERING_POTION;
            case BARRAGE -> Material.ARROW;
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
