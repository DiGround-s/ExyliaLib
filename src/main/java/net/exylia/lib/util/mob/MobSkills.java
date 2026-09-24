package net.exylia.lib.util.mob;

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
    }

    private static final List<Preset> LIBRARY = List.of(
            preset("slam", "GROUND SLAM", Material.ANVIL, "Leaps and shatters the floor around it.",
                    skill(MobSkill.Type.AREA_DAMAGE, 12, 5, 8, 0, "")
                            .withCast(cast("slam", MobSkill.Aim.SELF, 900).withWhen(MobSkill.Gate.ANY.withRange(0, 6)))),
            preset("meteor", "METEOR", Material.MAGMA_BLOCK, "Calls a burning rock down where you stand.",
                    skill(MobSkill.Type.AREA_DAMAGE, 14, 3, 10, 3, "")
                            .withCast(cast("meteor", MobSkill.Aim.GROUND, 1600).withWhen(MobSkill.Gate.ANY.withRange(0, 20)))),
            preset("blades", "BLADE RING", Material.NETHERITE_SWORD, "Spins a ring of blades around itself.",
                    skill(MobSkill.Type.ZONE, 14, 3, 3, 4, "")
                            .withCast(cast("blades", MobSkill.Aim.SELF, 500).withWhen(MobSkill.Gate.ANY.withNearby(6)))),
            preset("charge", "CHARGE", Material.IRON_HORSE_ARMOR, "Lowers its head and runs you down.",
                    skill(MobSkill.Type.DASH, 10, 12, 7, 0, "")
                            .withCast(cast("charge", MobSkill.Aim.AUTO, 700).withWhen(MobSkill.Gate.ANY.withRange(4, 16)))),
            preset("chain", "CHAIN LIGHTNING", Material.LIGHTNING_ROD, "Lightning that leaps from player to player.",
                    skill(MobSkill.Type.CHAIN, 12, 6, 4, 0, "4")
                            .withCast(cast("chain", MobSkill.Aim.AUTO, 500).withWhen(MobSkill.Gate.ANY.withRange(0, 16)))),
            preset("bubble", "SHIELD BUBBLE", Material.GLASS, "Wraps itself in a ward that blunts blows.",
                    skill(MobSkill.Type.SHIELD, 16, 0, 60, 5, "")
                            .withCast(cast("bubble", MobSkill.Aim.AUTO, 400).withWhen(MobSkill.Gate.ANY.withNearby(12)))),
            preset("portal", "SUMMON PORTAL", Material.CRYING_OBSIDIAN, "Opens portals and calls its minions through.",
                    skill(MobSkill.Type.SUMMON, 20, 3, 2, 0, "")
                            .withCast(cast("portal", MobSkill.Aim.AUTO, 1200).withWhen(MobSkill.Gate.ANY.withNearby(16)))),
            preset("miasma", "MIASMA", Material.SLIME_BALL, "Leaves a poison cloud where you stood.",
                    skill(MobSkill.Type.ZONE, 14, 3.5, 1, 6, "POISON|1|3")
                            .withCast(cast("miasma", MobSkill.Aim.GROUND, 800).withWhen(MobSkill.Gate.ANY.withRange(0, 16)))),
            preset("nova", "FROST NOVA", Material.BLUE_ICE, "Freezes everyone close in place.",
                    skill(MobSkill.Type.POTION, 12, 5, 0, 0, "SLOWNESS|3|3")
                            .withCast(cast("nova", MobSkill.Aim.SELF, 600).withWhen(MobSkill.Gate.ANY.withNearby(5)))),
            preset("blink", "BLINK", Material.ENDER_PEARL, "Vanishes and reappears behind you.",
                    skill(MobSkill.Type.TELEPORT, 10, 0, 0, 0, "")
                            .withCast(cast("blink", MobSkill.Aim.AUTO, 0).withWhen(MobSkill.Gate.ANY.withRange(3, 20)))),
            preset("renew", "RENEW", Material.GLISTERING_MELON_SLICE, "Channels a burst of healing.",
                    skill(MobSkill.Type.HEAL, 20, 0, 20, 0, "")
                            .withCast(cast("renew", MobSkill.Aim.AUTO, 800).withWhen(MobSkill.Gate.ANY.withHealth(0, 0.6)))),
            preset("enrage", "ENRAGE", Material.BLAZE_POWDER, "Roars and runs faster for a while.",
                    skill(MobSkill.Type.SPEED, 30, 0, 1.4, 8, "")
                            .withCast(cast("enrage", MobSkill.Aim.AUTO, 1200).withWhen(MobSkill.Gate.ANY.withHealth(0, 0.5)))),
            preset("rain", "ARROW RAIN", Material.ARROW, "Arrows fall on the spots it marks.",
                    skill(MobSkill.Type.BARRAGE, 14, 6, 5, 0, "4")
                            .withCast(cast("rain", MobSkill.Aim.AUTO, 600).withWhen(MobSkill.Gate.ANY.withRange(0, 20)))),
            preset("pounce", "POUNCE", Material.RABBIT_FOOT, "Crouches, then lands on you hard.",
                    skill(MobSkill.Type.LEAP, 8, 2, 1.2, 0, "")
                            .withCast(cast("pounce", MobSkill.Aim.AUTO, 500).withWhen(MobSkill.Gate.ANY.withRange(3, 10)))),
            preset("hook", "HOOK", Material.CHAIN, "Drags you to it on a chain.",
                    skill(MobSkill.Type.PULL, 10, 0, 1.4, 0, "")
                            .withCast(cast("hook", MobSkill.Aim.AUTO, 300).withWhen(MobSkill.Gate.ANY.withRange(5, 14)))),
            preset("volley", "VOLLEY", Material.FIRE_CHARGE, "Takes aim, then fires at you.",
                    skill(MobSkill.Type.PROJECTILE, 8, 0, 1.5, 0, "FIREBALL")
                            .withCast(cast("volley", MobSkill.Aim.AUTO, 400).withWhen(MobSkill.Gate.ANY.withRange(4, 24)))));

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

    private static Preset preset(String id, String label, Material icon, String blurb, MobSkill skill) {
        return new Preset(id, label, icon, blurb, skill);
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
