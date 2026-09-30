package net.exylia.lib.util.mob;

import net.exylia.lib.text.Phrases;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The skill library: ready-made skills, and the styles skills are drawn in.
 *
 * <pre>{@code
 * MobSkill slam = MobSkills.preset("slam").skill();
 * template = template.withSkills(List.of(slam, MobSkills.preset("renew").skill()
 *         .withTrigger(MobSkill.Trigger.LOW_HEALTH)));
 * }</pre>
 *
 * <h2>Presets are data</h2>
 * A preset is an ordinary {@link MobSkill}: a type, its numbers and a
 * {@link MobSkill.Cast} with a wind-up, an aim and a style. Nothing about it is
 * special once it is on a template; every field edits and stores like any other
 * skill's.
 *
 * <h2>Styles are drawn, not stored</h2>
 * A style is how a skill looks: its telegraph, its release and its impact,
 * drawn with packets and never touching gameplay. {@link MobSkill.Cast#style()}
 * names one of {@link #STYLES}; blank draws {@link #autoStyle}, the one that
 * suits the type, but only while the skill has no {@code effect} lines of its
 * own, so an older skill with a hand-made look keeps exactly that look.
 *
 * @since 1.200.0
 */
public final class MobSkills {

    /** The style that draws nothing. */
    public static final String NO_STYLE = "none";

    /** Every style there is, in the order an editor lists them. */
    public static final List<String> STYLES = List.of(
            "slam", "meteor", "blades", "charge", "chain", "bubble", "portal", "miasma", "nova", "blink",
            "renew", "enrage", "rain", "pounce", "hook", "volley",
            "cleave", "fissure", "vortex", "dread", "eruption", "smite",
            "judgement", "supernova", "tempest", "starfall", "prism",
            "burst", "hop", "inflate", "zoom", "shrink", "puff");

    private MobSkills() {
        throw new AssertionError("No instances.");
    }

    /**
     * One ready-made skill.
     *
     * @param id    its style's id, which is also how it is found
     * @param label what an editor calls it, plain text in capitals
     * @param icon  what an editor shows it as
     * @param blurb one line saying what it does, as a player would put it
     * @param skill the skill, on the trigger it suits best
     */
    public record Preset(@NotNull String id, @NotNull String label, @NotNull Material icon, @NotNull String blurb,
                         @NotNull MobSkill skill) {

        /** What an editor calls it, in the library's language as the screen opens. */
        @Override
        public @NotNull String label() {
            return switch (id) {
                case "slam" -> Phrases.tr("GROUND SLAM");
                case "meteor" -> Phrases.tr("METEOR");
                case "blades" -> Phrases.tr("BLADE RING");
                case "charge" -> Phrases.tr("CHARGE");
                case "chain" -> Phrases.tr("CHAIN LIGHTNING");
                case "bubble" -> Phrases.tr("SHIELD BUBBLE");
                case "portal" -> Phrases.tr("SUMMON PORTAL");
                case "miasma" -> Phrases.tr("MIASMA");
                case "nova" -> Phrases.tr("FROST NOVA");
                case "blink" -> Phrases.tr("BLINK");
                case "renew" -> Phrases.tr("RENEW");
                case "enrage" -> Phrases.tr("ENRAGE");
                case "rain" -> Phrases.tr("ARROW RAIN");
                case "pounce" -> Phrases.tr("POUNCE");
                case "hook" -> Phrases.tr("HOOK");
                case "volley" -> Phrases.tr("VOLLEY");
                case "cleave" -> Phrases.tr("CLEAVE");
                case "fissure" -> Phrases.tr("EARTHSPLITTER");
                case "vortex" -> Phrases.tr("GRAVITY WELL");
                case "dread" -> Phrases.tr("DREAD");
                case "eruption" -> Phrases.tr("ERUPTION");
                case "smite" -> Phrases.tr("SMITE");
                case "judgement" -> Phrases.tr("JUDGEMENT");
                case "supernova" -> Phrases.tr("SUPERNOVA");
                case "tempest" -> Phrases.tr("TEMPEST");
                case "starfall" -> Phrases.tr("STARFALL");
                case "prism" -> Phrases.tr("PRISM BEAM");
                default -> label;
            };
        }

        /** What it does, in the library's language as the screen opens. */
        @Override
        public @NotNull String blurb() {
            return switch (id) {
                case "slam" -> Phrases.tr("Leaps and shatters the floor around it.");
                case "meteor" -> Phrases.tr("Calls a burning rock down where you stand.");
                case "blades" -> Phrases.tr("Spins a ring of blades around itself.");
                case "charge" -> Phrases.tr("Lowers its head and runs you down.");
                case "chain" -> Phrases.tr("Lightning that leaps from player to player.");
                case "bubble" -> Phrases.tr("Wraps itself in a ward that blunts blows.");
                case "portal" -> Phrases.tr("Opens portals and calls its minions through.");
                case "miasma" -> Phrases.tr("Leaves a poison cloud where you stood.");
                case "nova" -> Phrases.tr("Freezes everyone close in place.");
                case "blink" -> Phrases.tr("Vanishes and reappears behind you.");
                case "renew" -> Phrases.tr("Channels a burst of healing.");
                case "enrage" -> Phrases.tr("Roars and runs faster for a while.");
                case "rain" -> Phrases.tr("Arrows fall on the spots it marks.");
                case "pounce" -> Phrases.tr("Crouches, then lands on you hard.");
                case "hook" -> Phrases.tr("Drags you to it on a chain.");
                case "volley" -> Phrases.tr("Takes aim, then fires at you.");
                case "cleave" -> Phrases.tr("Winds back, then sweeps everything ahead.");
                case "fissure" -> Phrases.tr("Splits the ground in a line at you.");
                case "vortex" -> Phrases.tr("Drags everyone around it inwards.");
                case "dread" -> Phrases.tr("A shriek that drowns everyone in darkness.");
                case "eruption" -> Phrases.tr("The ground under you boils, then bursts.");
                case "smite" -> Phrases.tr("Marks a spot, then calls a bolt onto it.");
                case "judgement" -> Phrases.tr("A giant blade falls from the sky onto you.");
                case "supernova" -> Phrases.tr("Swallows the light, then bursts like a star.");
                case "tempest" -> Phrases.tr("Spins up a tornado that hurls you away.");
                case "starfall" -> Phrases.tr("Draws a constellation, then drops its stars.");
                case "prism" -> Phrases.tr("Focuses a crystal, then fires a searing beam.");
                default -> blurb;
            };
        }
    }

    private static final List<Preset> LIBRARY = List.of(
            preset("slam", Material.ANVIL,
                    skill(MobSkill.Type.AREA_DAMAGE, 12, 5, 8, 0, "")
                            .withCast(cast("slam", MobSkill.Aim.SELF, 900).withWhen(MobSkill.Gate.ANY.withRange(0, 6)))),
            preset("meteor", Material.MAGMA_BLOCK,
                    skill(MobSkill.Type.AREA_DAMAGE, 14, 3, 10, 3, "")
                            .withCast(cast("meteor", MobSkill.Aim.GROUND, 1600).withWhen(MobSkill.Gate.ANY.withRange(0, 20)))),
            preset("blades", Material.NETHERITE_SWORD,
                    skill(MobSkill.Type.ZONE, 14, 3, 3, 4, "")
                            .withCast(cast("blades", MobSkill.Aim.SELF, 500).withWhen(MobSkill.Gate.ANY.withNearby(6)))),
            preset("charge", Material.IRON_HORSE_ARMOR,
                    skill(MobSkill.Type.DASH, 10, 12, 7, 0, "")
                            .withCast(cast("charge", MobSkill.Aim.AUTO, 700).withWhen(MobSkill.Gate.ANY.withRange(4, 16)))),
            preset("chain", Material.LIGHTNING_ROD,
                    skill(MobSkill.Type.CHAIN, 12, 6, 4, 0, "4")
                            .withCast(cast("chain", MobSkill.Aim.AUTO, 500).withWhen(MobSkill.Gate.ANY.withRange(0, 16)))),
            preset("bubble", Material.GLASS,
                    skill(MobSkill.Type.SHIELD, 16, 0, 60, 5, "")
                            .withCast(cast("bubble", MobSkill.Aim.AUTO, 400).withWhen(MobSkill.Gate.ANY.withNearby(12)))),
            preset("portal", Material.CRYING_OBSIDIAN,
                    skill(MobSkill.Type.SUMMON, 20, 3, 2, 0, "")
                            .withCast(cast("portal", MobSkill.Aim.AUTO, 1200).withWhen(MobSkill.Gate.ANY.withNearby(16)))),
            preset("miasma", Material.SLIME_BALL,
                    skill(MobSkill.Type.ZONE, 14, 3.5, 1.5, 6, "POISON|2|3")
                            .withCast(cast("miasma", MobSkill.Aim.GROUND, 800).withWhen(MobSkill.Gate.ANY.withRange(0, 16)))),
            preset("nova", Material.BLUE_ICE,
                    skill(MobSkill.Type.POTION, 12, 5, 0, 0, "SLOWNESS|6|3")
                            .withCast(cast("nova", MobSkill.Aim.SELF, 600).withWhen(MobSkill.Gate.ANY.withNearby(5)))),
            preset("blink", Material.ENDER_PEARL,
                    skill(MobSkill.Type.TELEPORT, 10, 0, 0, 0, "")
                            .withCast(cast("blink", MobSkill.Aim.AUTO, 0).withWhen(MobSkill.Gate.ANY.withRange(3, 20)))),
            preset("renew", Material.GLISTERING_MELON_SLICE,
                    skill(MobSkill.Type.HEAL, 20, 0, 20, 0, "")
                            .withCast(cast("renew", MobSkill.Aim.AUTO, 800).withWhen(MobSkill.Gate.ANY.withHealth(0, 0.6)))),
            preset("enrage", Material.BLAZE_POWDER,
                    skill(MobSkill.Type.SPEED, 30, 0, 1.6, 8, "")
                            .withCast(cast("enrage", MobSkill.Aim.AUTO, 1200).withWhen(MobSkill.Gate.ANY.withHealth(0, 0.5)))),
            preset("rain", Material.ARROW,
                    skill(MobSkill.Type.BARRAGE, 14, 6, 5, 0, "4")
                            .withCast(cast("rain", MobSkill.Aim.AUTO, 600).withWhen(MobSkill.Gate.ANY.withRange(0, 20)))),
            preset("pounce", Material.RABBIT_FOOT,
                    skill(MobSkill.Type.LEAP, 8, 2, 1.2, 0, "")
                            .withCast(cast("pounce", MobSkill.Aim.AUTO, 500).withWhen(MobSkill.Gate.ANY.withRange(3, 10)))),
            preset("hook", Material.CHAIN,
                    skill(MobSkill.Type.PULL, 10, 0, 1.4, 0, "")
                            .withCast(cast("hook", MobSkill.Aim.AUTO, 300).withWhen(MobSkill.Gate.ANY.withRange(5, 14)))),
            preset("volley", Material.FIRE_CHARGE,
                    skill(MobSkill.Type.PROJECTILE, 8, 0, 1.5, 0, "FIREBALL")
                            .withCast(cast("volley", MobSkill.Aim.AUTO, 400).withWhen(MobSkill.Gate.ANY.withRange(4, 24)))),
            preset("cleave", Material.NETHERITE_AXE,
                    skill(MobSkill.Type.AREA_DAMAGE, 9, 4.5, 9, 0, "")
                            .withCast(cast("cleave", MobSkill.Aim.CONE, 700).withSpread(120)
                                    .withWhen(MobSkill.Gate.ANY.withRange(0, 4.5)))),
            preset("fissure", Material.CRACKED_DEEPSLATE_BRICKS,
                    skill(MobSkill.Type.AREA_DAMAGE, 14, 12, 8, 0, "")
                            .withCast(cast("fissure", MobSkill.Aim.LINE, 1000).withSpread(2.4)
                                    .withWhen(MobSkill.Gate.ANY.withRange(3, 12)))),
            preset("vortex", Material.HEAVY_CORE,
                    skill(MobSkill.Type.PULL, 16, 8, 1.1, 0, "")
                            .withCast(cast("vortex", MobSkill.Aim.ALL, 900).withWhen(MobSkill.Gate.ANY.withRange(4, 8)))),
            preset("dread", Material.SCULK_SHRIEKER,
                    skill(MobSkill.Type.POTION, 25, 10, 0, 0, "DARKNESS|1|6")
                            .withCast(cast("dread", MobSkill.Aim.AUTO, 1000).withWhen(MobSkill.Gate.ANY.withNearby(10)))),
            preset("eruption", Material.MAGMA_CREAM,
                    skill(MobSkill.Type.AREA_DAMAGE, 15, 2.5, 9, 4, "")
                            .withCast(cast("eruption", MobSkill.Aim.GROUND, 1300).withWhen(MobSkill.Gate.ANY.withRange(0, 18)))),
            preset("smite", Material.TRIDENT,
                    skill(MobSkill.Type.LIGHTNING, 12, 0, 7, 0, "")
                            .withCast(cast("smite", MobSkill.Aim.GROUND, 1100).withWhen(MobSkill.Gate.ANY.withRange(0, 20)))),
            preset("judgement", Material.GOLDEN_SWORD,
                    skill(MobSkill.Type.AREA_DAMAGE, 22, 4, 12, 0, "")
                            .withCast(cast("judgement", MobSkill.Aim.GROUND, 2200).withWhen(MobSkill.Gate.ANY.withRange(0, 20)))),
            preset("supernova", Material.NETHER_STAR,
                    skill(MobSkill.Type.AREA_DAMAGE, 35, 7, 14, 3, "")
                            .withCast(cast("supernova", MobSkill.Aim.SELF, 2600)
                                    .withWhen(MobSkill.Gate.ANY.withHealth(0, 0.5).withNearby(7)))),
            preset("tempest", Material.WIND_CHARGE,
                    skill(MobSkill.Type.PUSH, 18, 6, 1.8, 0, "")
                            .withCast(cast("tempest", MobSkill.Aim.SELF, 1500).withWhen(MobSkill.Gate.ANY.withNearby(5)))),
            preset("starfall", Material.AMETHYST_CLUSTER,
                    skill(MobSkill.Type.BARRAGE, 20, 7, 9, 0, "5")
                            .withCast(cast("starfall", MobSkill.Aim.AUTO, 1400).withWhen(MobSkill.Gate.ANY.withRange(0, 20)))),
            preset("prism", Material.AMETHYST_SHARD,
                    skill(MobSkill.Type.AREA_DAMAGE, 16, 16, 10, 0, "")
                            .withCast(cast("prism", MobSkill.Aim.LINE, 1600).withSpread(1.4)
                                    .withWhen(MobSkill.Gate.ANY.withRange(4, 16)))));

    /** Every preset, in the order an editor lists them. */
    public static @NotNull List<Preset> library() {
        return LIBRARY;
    }

    /**
     * A preset by id, in any case.
     *
     * @return the preset, or {@code null} when there is none by that id
     */
    public static @Nullable Preset preset(@NotNull String id) {
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (Preset preset : LIBRARY) {
            if (preset.id().equals(wanted)) return preset;
        }
        return null;
    }

    /**
     * The style that suits a skill's type, which a blank {@link MobSkill.Cast#style()}
     * draws.
     *
     * <table>
     *   <caption>By type</caption>
     *   <tr><td>AREA_DAMAGE</td><td>slam</td></tr>
     *   <tr><td>LEAP / PULL / PUSH</td><td>pounce / hook / burst</td></tr>
     *   <tr><td>POTION</td><td>nova for SLOWNESS with a radius, else puff</td></tr>
     *   <tr><td>SUMMON / TELEPORT / HEAL</td><td>portal / blink / renew</td></tr>
     *   <tr><td>LIGHTNING / CHAIN</td><td>chain</td></tr>
     *   <tr><td>PROJECTILE</td><td>volley</td></tr>
     *   <tr><td>IGNITE</td><td>puff, in fire</td></tr>
     *   <tr><td>JUMP / SIZE / SPEED / BABY</td><td>hop / inflate / zoom / shrink</td></tr>
     *   <tr><td>DASH / SHIELD / BARRAGE</td><td>charge / bubble / rain</td></tr>
     *   <tr><td>ZONE</td><td>blades aimed at SELF, else miasma</td></tr>
     *   <tr><td>EFFECT / COMMAND</td><td>none</td></tr>
     * </table>
     *
     * @param skill the skill
     * @return a style id, or {@link #NO_STYLE}
     */
    public static @NotNull String autoStyle(@NotNull MobSkill skill) {
        return switch (skill.type()) {
            case AREA_DAMAGE -> "slam";
            case LEAP -> "pounce";
            case PULL -> "hook";
            case PUSH -> "burst";
            case POTION -> skill.radius() > 0 && skill.text().trim().toUpperCase(Locale.ROOT).startsWith("SLOWNESS")
                    ? "nova" : "puff";
            case SUMMON -> "portal";
            case LIGHTNING, CHAIN -> "chain";
            case PROJECTILE -> "volley";
            case HEAL -> "renew";
            case TELEPORT -> "blink";
            case IGNITE -> "puff";
            case JUMP -> "hop";
            case SIZE -> "inflate";
            case SPEED -> "zoom";
            case BABY -> "shrink";
            case DASH -> "charge";
            case SHIELD -> "bubble";
            case ZONE -> skill.cast().aim() == MobSkill.Aim.SELF ? "blades" : "miasma";
            case BARRAGE -> "rain";
            case EFFECT, COMMAND -> NO_STYLE;
        };
    }

    /**
     * The style a skill is drawn in: its own, else {@link #autoStyle} while it
     * has no {@code effect} lines, else none.
     *
     * @param skill the skill
     * @return a style id, or {@link #NO_STYLE}
     */
    public static @NotNull String styleOf(@NotNull MobSkill skill) {
        String written = skill.cast().style().toLowerCase(Locale.ROOT);
        if (written.equals(NO_STYLE)) return NO_STYLE;
        if (STYLES.contains(written)) return written;
        return skill.effect().isBlank() ? autoStyle(skill) : NO_STYLE;
    }

    /** A library preset: its label and blurb are the ones {@link Preset#label()} and {@link Preset#blurb()} translate. */
    private static Preset preset(String id, Material icon, MobSkill skill) {
        return new Preset(id, "", icon, "", skill);
    }

    private static MobSkill skill(MobSkill.Type type, int cooldownSeconds, double radius, double amount,
                                  int seconds, String text) {
        return new MobSkill(MobSkill.Trigger.INTERVAL, type, 1, Duration.ofSeconds(cooldownSeconds), 0.3, radius,
                amount, Duration.ofSeconds(seconds), text, "", MobSkill.Cast.NONE);
    }

    private static MobSkill.Cast cast(String style, MobSkill.Aim aim, long windupMillis) {
        return MobSkill.Cast.NONE.withName(style).withAim(aim).withWindup(Duration.ofMillis(windupMillis))
                .withStyle(style);
    }
}
