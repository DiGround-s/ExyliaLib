package net.exylia.lib.api.practicebot;

/**
 * Everything about a bot that would otherwise be the server's business.
 *
 * <h2>Why this exists</h2>
 * A bot spawned through the API used to be a bot spawned through {@code /bot}
 * with a different mode on it: its health, its totems, whether it could be
 * killed at all, whether its crystal blasts ate the terrain - every one of those
 * came out of the bot plugin's own {@code config.yml}. That is right for a
 * sandbox dummy and wrong for anything else. An admin who turns a practice
 * dummy invincible and gives it endless totems has said something about their
 * sandbox; a duel plugin that then spawns an opponent gets an unkillable wall,
 * and there was nothing it could do about it.
 *
 * <p>So a bot spawned through the API is built on a vanilla body instead -
 * twenty health, killable, knocked back like a player, and no totems beyond the
 * ones its kit carries - and reads the server's configuration only for the
 * things that are genuinely tuning rather than a sandbox cheat: reach, cadence,
 * movement, and how each mode plays.
 *
 * <p>This type changes either. Every field is optional and every one left null
 * keeps the default it would have had, so a caller that cares about two of them
 * says two of them and nothing else moves.
 *
 * <pre>{@code
 * BotTuning tuning = BotTuning.builder()
 *         .breakBlocks(false)      // the arena gets reused
 *         .enderPearls(false)      // and it is too small to pearl across
 *         .build();
 * }</pre>
 *
 * <p>The counterpart to {@link BotKit}: the kit says what the bot brought, this
 * says how the bot is allowed to behave with it.
 *
 * @param maxHealth        maximum health in half-hearts
 * @param invincible       whether it can be killed at all. False unless asked -
 *                         see {@link #trainingDummy()}
 * @param spareTotems      totems it re-arms with after one pops, on top of the
 *                         one it is holding. {@code 0} means the one it holds is
 *                         all it gets, {@code -1} means it never runs out. Sent
 *                         a {@link BotKit}, the bot counts its own out of the kit
 *                         and this is only needed to overrule that
 * @param attackDamage     a fully charged non-critical hit, in half-hearts.
 *                         Ignored when a {@link BotKit} is sent - the weapon in
 *                         the kit answers this better
 * @param attackReach      how far it can hit from, in blocks. Vanilla is 3.0
 * @param attackSpeed      multiplies the cadence its mode chose. 1.0 leaves it
 * @param movementSpeed    multiplies walk and sprint speed. 1.0 is a player
 * @param followDistance   how close it stays when it is not fighting
 * @param antiKnockback    whether it ignores knockback entirely
 * @param slowFalling      whether it falls slowly, permanently
 * @param shieldDisableSeconds how long an axe hit keeps its shield down
 * @param respawnDelaySeconds  how long before it comes back, for a bot spawned
 *                         with {@link BotSpec#respawn()}
 * @param crystalRange     how close it starts a crystal chain, in blocks
 * @param crystalSelfPreservationPercent below this health it stops placing
 *                         crystals against itself
 * @param respawnAnchors   whether it also uses charged respawn anchors
 * @param crystalTraps     whether it walls a low opponent in before detonating
 * @param enderPearls      whether it pearls to close the gap
 * @param breakBlocks      whether its blasts destroy terrain and its obsidian
 *                         stays put. False makes it clean up after itself, which
 *                         is what an arena that gets reused wants
 * @param healPotionPercent health percentage at which it splashes Instant Health
 * @param potApplePercent  health percentage at which it eats, in pot PvP
 * @param enchantedApples  whether it eats notch apples when no kit says otherwise
 * @param uhcApplePercent  health percentage at which it eats, in UHC
 * @param cobwebs          whether it webs the opponent
 * @param lava             whether it pours lava
 * @param water            whether it carries a water bucket
 *
 * @since 1.127.0
 */
public record BotTuning(
        Double maxHealth,
        Boolean invincible,
        Integer spareTotems,
        Double attackDamage,
        Double attackReach,
        Double attackSpeed,
        Double movementSpeed,
        Double followDistance,
        Boolean antiKnockback,
        Boolean slowFalling,
        Double shieldDisableSeconds,
        Double respawnDelaySeconds,
        Double crystalRange,
        Double crystalSelfPreservationPercent,
        Boolean respawnAnchors,
        Boolean crystalTraps,
        Boolean enderPearls,
        Boolean breakBlocks,
        Double healPotionPercent,
        Double potApplePercent,
        Boolean enchantedApples,
        Double uhcApplePercent,
        Boolean cobwebs,
        Boolean lava,
        Boolean water) {

    /** Totems that never run out. A practice-dummy answer, never a default. */
    public static final int UNLIMITED_TOTEMS = -1;

    private static final BotTuning NOTHING = builder().build();

    /** Nothing overridden. What a bot spawned without a tuning is built on. */
    public static BotTuning none() {
        return NOTHING;
    }

    /**
     * The practice dummy, asked for on purpose.
     *
     * <p>Cannot be killed and never runs out of totems - what the bot plugin's
     * own sandbox is usually configured to be, and what an API bot is
     * deliberately <em>not</em> unless something says this.
     *
     * <p>Worth knowing why it has to be said: a bot spawned through the API is
     * built on a vanilla body - twenty health, killable, knocked back like
     * everybody, and no totems beyond the ones its kit carries. It used to
     * inherit the server's sandbox numbers instead, which is how an integration
     * ended up with an opponent that could not be killed and had never been
     * asked to be.
     */
    public static BotTuning trainingDummy() {
        return builder()
                .invincible(true)
                .spareTotems(UNLIMITED_TOTEMS)
                .build();
    }

    /** Whether every field is unset, in which case applying it does nothing. */
    public boolean isEmpty() {
        return equals(NOTHING);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** This tuning, ready to be changed into another one. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.maxHealth = maxHealth;
        builder.invincible = invincible;
        builder.spareTotems = spareTotems;
        builder.attackDamage = attackDamage;
        builder.attackReach = attackReach;
        builder.attackSpeed = attackSpeed;
        builder.movementSpeed = movementSpeed;
        builder.followDistance = followDistance;
        builder.antiKnockback = antiKnockback;
        builder.slowFalling = slowFalling;
        builder.shieldDisableSeconds = shieldDisableSeconds;
        builder.respawnDelaySeconds = respawnDelaySeconds;
        builder.crystalRange = crystalRange;
        builder.crystalSelfPreservationPercent = crystalSelfPreservationPercent;
        builder.respawnAnchors = respawnAnchors;
        builder.crystalTraps = crystalTraps;
        builder.enderPearls = enderPearls;
        builder.breakBlocks = breakBlocks;
        builder.healPotionPercent = healPotionPercent;
        builder.potApplePercent = potApplePercent;
        builder.enchantedApples = enchantedApples;
        builder.uhcApplePercent = uhcApplePercent;
        builder.cobwebs = cobwebs;
        builder.lava = lava;
        builder.water = water;
        return builder;
    }

    /** Says only what it wants to change. Everything unsaid stays the server's. */
    public static final class Builder {

        private Double maxHealth;
        private Boolean invincible;
        private Integer spareTotems;
        private Double attackDamage;
        private Double attackReach;
        private Double attackSpeed;
        private Double movementSpeed;
        private Double followDistance;
        private Boolean antiKnockback;
        private Boolean slowFalling;
        private Double shieldDisableSeconds;
        private Double respawnDelaySeconds;
        private Double crystalRange;
        private Double crystalSelfPreservationPercent;
        private Boolean respawnAnchors;
        private Boolean crystalTraps;
        private Boolean enderPearls;
        private Boolean breakBlocks;
        private Double healPotionPercent;
        private Double potApplePercent;
        private Boolean enchantedApples;
        private Double uhcApplePercent;
        private Boolean cobwebs;
        private Boolean lava;
        private Boolean water;

        private Builder() {}

        public Builder maxHealth(double value) {
            this.maxHealth = value;
            return this;
        }

        public Builder invincible(boolean value) {
            this.invincible = value;
            return this;
        }

        /** @param value spare totems, or {@link #UNLIMITED_TOTEMS} for endless */
        public Builder spareTotems(int value) {
            this.spareTotems = value;
            return this;
        }

        public Builder attackDamage(double value) {
            this.attackDamage = value;
            return this;
        }

        public Builder attackReach(double value) {
            this.attackReach = value;
            return this;
        }

        public Builder attackSpeed(double value) {
            this.attackSpeed = value;
            return this;
        }

        public Builder movementSpeed(double value) {
            this.movementSpeed = value;
            return this;
        }

        public Builder followDistance(double value) {
            this.followDistance = value;
            return this;
        }

        public Builder antiKnockback(boolean value) {
            this.antiKnockback = value;
            return this;
        }

        public Builder slowFalling(boolean value) {
            this.slowFalling = value;
            return this;
        }

        public Builder shieldDisableSeconds(double value) {
            this.shieldDisableSeconds = value;
            return this;
        }

        public Builder respawnDelaySeconds(double value) {
            this.respawnDelaySeconds = value;
            return this;
        }

        public Builder crystalRange(double value) {
            this.crystalRange = value;
            return this;
        }

        public Builder crystalSelfPreservationPercent(double value) {
            this.crystalSelfPreservationPercent = value;
            return this;
        }

        public Builder respawnAnchors(boolean value) {
            this.respawnAnchors = value;
            return this;
        }

        public Builder crystalTraps(boolean value) {
            this.crystalTraps = value;
            return this;
        }

        public Builder enderPearls(boolean value) {
            this.enderPearls = value;
            return this;
        }

        public Builder breakBlocks(boolean value) {
            this.breakBlocks = value;
            return this;
        }

        public Builder healPotionPercent(double value) {
            this.healPotionPercent = value;
            return this;
        }

        public Builder potApplePercent(double value) {
            this.potApplePercent = value;
            return this;
        }

        public Builder enchantedApples(boolean value) {
            this.enchantedApples = value;
            return this;
        }

        public Builder uhcApplePercent(double value) {
            this.uhcApplePercent = value;
            return this;
        }

        public Builder cobwebs(boolean value) {
            this.cobwebs = value;
            return this;
        }

        public Builder lava(boolean value) {
            this.lava = value;
            return this;
        }

        public Builder water(boolean value) {
            this.water = value;
            return this;
        }

        public BotTuning build() {
            return new BotTuning(maxHealth, invincible, spareTotems, attackDamage, attackReach,
                    attackSpeed, movementSpeed, followDistance, antiKnockback, slowFalling,
                    shieldDisableSeconds, respawnDelaySeconds, crystalRange,
                    crystalSelfPreservationPercent, respawnAnchors, crystalTraps, enderPearls,
                    breakBlocks, healPotionPercent, potApplePercent, enchantedApples,
                    uhcApplePercent, cobwebs, lava, water);
        }
    }
}
