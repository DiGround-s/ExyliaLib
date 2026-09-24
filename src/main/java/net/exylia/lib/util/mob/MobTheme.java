package net.exylia.lib.util.mob;

import org.jetbrains.annotations.NotNull;

/**
 * The colours skill styles are drawn in, by role, so a server recolours every
 * telegraph and every spell from one place.
 *
 * <pre>{@code
 * mobs.theme(MobTheme.DEFAULT.withDanger("{accent}").withFrost("#b8f1ff"));
 * }</pre>
 *
 * <p>Each value is a palette token such as {@code {warning}}, which follows the
 * palette when it is reloaded, or a {@code #rrggbb} colour. A value that is
 * neither draws the role's default. A skill's own {@link MobSkill.Cast#tint()}
 * replaces the main role of its style.
 *
 * @param telegraph the warning on the ground before an attack lands
 * @param danger    what hurts: a meteor's circle, a charge's lane, a barrage
 * @param heal      healing
 * @param shield    shields and wards
 * @param arcane    summons, blinks, shockwaves that only push
 * @param fire      fire and meteors
 * @param frost     ice and slowness
 * @param poison    poison clouds
 * @param shock     lightning
 * @param crit      a hard hit landing
 * @since 1.200.0
 */
public record MobTheme(@NotNull String telegraph, @NotNull String danger, @NotNull String heal,
                       @NotNull String shield, @NotNull String arcane, @NotNull String fire,
                       @NotNull String frost, @NotNull String poison, @NotNull String shock,
                       @NotNull String crit) {

    /**
     * The palette's warning, error, success, info and primary for the general
     * roles; the elements have no token of their own, so they are colours.
     */
    public static final MobTheme DEFAULT = new MobTheme("{warning}", "{error}", "{success}", "{info}",
            "{primary}", "#ff7a1a", "#9be7ff", "#7bc043", "#8fd3ff", "{warning}");

    public MobTheme {
        telegraph = clean(telegraph, "{warning}");
        danger = clean(danger, "{error}");
        heal = clean(heal, "{success}");
        shield = clean(shield, "{info}");
        arcane = clean(arcane, "{primary}");
        fire = clean(fire, "#ff7a1a");
        frost = clean(frost, "#9be7ff");
        poison = clean(poison, "#7bc043");
        shock = clean(shock, "#8fd3ff");
        crit = clean(crit, "{warning}");
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public @NotNull MobTheme withTelegraph(@NotNull String telegraph) {
        return new MobTheme(telegraph, danger, heal, shield, arcane, fire, frost, poison, shock, crit);
    }

    public @NotNull MobTheme withDanger(@NotNull String danger) {
        return new MobTheme(telegraph, danger, heal, shield, arcane, fire, frost, poison, shock, crit);
    }

    public @NotNull MobTheme withHeal(@NotNull String heal) {
        return new MobTheme(telegraph, danger, heal, shield, arcane, fire, frost, poison, shock, crit);
    }

    public @NotNull MobTheme withShield(@NotNull String shield) {
        return new MobTheme(telegraph, danger, heal, shield, arcane, fire, frost, poison, shock, crit);
    }

    public @NotNull MobTheme withArcane(@NotNull String arcane) {
        return new MobTheme(telegraph, danger, heal, shield, arcane, fire, frost, poison, shock, crit);
    }

    public @NotNull MobTheme withFire(@NotNull String fire) {
        return new MobTheme(telegraph, danger, heal, shield, arcane, fire, frost, poison, shock, crit);
    }

    public @NotNull MobTheme withFrost(@NotNull String frost) {
        return new MobTheme(telegraph, danger, heal, shield, arcane, fire, frost, poison, shock, crit);
    }

    public @NotNull MobTheme withPoison(@NotNull String poison) {
        return new MobTheme(telegraph, danger, heal, shield, arcane, fire, frost, poison, shock, crit);
    }

    public @NotNull MobTheme withShock(@NotNull String shock) {
        return new MobTheme(telegraph, danger, heal, shield, arcane, fire, frost, poison, shock, crit);
    }

    public @NotNull MobTheme withCrit(@NotNull String crit) {
        return new MobTheme(telegraph, danger, heal, shield, arcane, fire, frost, poison, shock, crit);
    }

    /** The roles, in the order a style names them. */
    public enum Role {
        TELEGRAPH, DANGER, HEAL, SHIELD, ARCANE, FIRE, FROST, POISON, SHOCK, CRIT
    }

    /**
     * What a role is written as here.
     *
     * @param role the role
     * @return its token or colour, as written
     */
    public @NotNull String of(@NotNull Role role) {
        return switch (role) {
            case TELEGRAPH -> telegraph;
            case DANGER -> danger;
            case HEAL -> heal;
            case SHIELD -> shield;
            case ARCANE -> arcane;
            case FIRE -> fire;
            case FROST -> frost;
            case POISON -> poison;
            case SHOCK -> shock;
            case CRIT -> crit;
        };
    }
}
