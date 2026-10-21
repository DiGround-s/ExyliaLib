package net.exylia.lib.util.mob;

import org.jetbrains.annotations.NotNull;

/**
 * What a model mob sounds like: its own voice instead of the vanilla one.
 *
 * <pre>{@code
 * MobSounds golem = MobSounds.NONE
 *         .withAmbient("minecraft:entity.iron_golem.repair|0.8|0.6")
 *         .withStep("minecraft:entity.iron_golem.step|1|0.7")
 *         .withHurt("minecraft:entity.iron_golem.hurt|1|0.6")
 *         .withDeath("minecraft:entity.iron_golem.death|1|0.6")
 *         .withAttack("minecraft:entity.iron_golem.attack|1|0.6");
 * }</pre>
 *
 * <p>A mob that wears a model is silent: the carrier underneath would groan,
 * step and die in its own vanilla voice. Every sound it makes is one of these,
 * or one its animations name (a Blockbench sound key).
 *
 * <p>Each value is {@code sound|volume|pitch}, the same inline form menu
 * sounds use: a Bukkit name ({@code ENTITY_IRON_GOLEM_STEP}) or a key
 * ({@code minecraft:entity.iron_golem.step}); volume and pitch may be left out.
 * Blank is no sound.
 *
 * @param ambient  now and then while it lives, every {@code every} seconds on average
 * @param step     each stride it walks on the ground
 * @param hurt     each hit it takes
 * @param death    as it dies
 * @param attack   each blow it strikes
 * @param every    seconds between ambient sounds, on average; {@code 0} never
 * @since 1.324.0
 */
public record MobSounds(@NotNull String ambient, @NotNull String step, @NotNull String hurt,
                        @NotNull String death, @NotNull String attack, double every) {

    /** Silent. */
    public static final MobSounds NONE = new MobSounds("", "", "", "", "", 8);

    public MobSounds {
        ambient = clean(ambient);
        step = clean(step);
        hurt = clean(hurt);
        death = clean(death);
        attack = clean(attack);
        every = Double.isFinite(every) ? Math.clamp(every, 0, 600) : 8;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** Whether it makes no sound at all. */
    public boolean isEmpty() {
        return ambient.isEmpty() && step.isEmpty() && hurt.isEmpty() && death.isEmpty() && attack.isEmpty();
    }

    public @NotNull MobSounds withAmbient(@NotNull String ambient) {
        return new MobSounds(ambient, step, hurt, death, attack, every);
    }

    public @NotNull MobSounds withStep(@NotNull String step) {
        return new MobSounds(ambient, step, hurt, death, attack, every);
    }

    public @NotNull MobSounds withHurt(@NotNull String hurt) {
        return new MobSounds(ambient, step, hurt, death, attack, every);
    }

    public @NotNull MobSounds withDeath(@NotNull String death) {
        return new MobSounds(ambient, step, hurt, death, attack, every);
    }

    public @NotNull MobSounds withAttack(@NotNull String attack) {
        return new MobSounds(ambient, step, hurt, death, attack, every);
    }

    public @NotNull MobSounds withEvery(double every) {
        return new MobSounds(ambient, step, hurt, death, attack, every);
    }
}
