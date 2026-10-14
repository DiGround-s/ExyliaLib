package net.exylia.lib.npc;

import net.exylia.lib.config.Comment;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * When a placed NPC plays an animation on its own: when it is clicked, every so often,
 * and when a player walks up to it.
 *
 * <pre>{@code
 * NpcSpec spec = NpcSpec.builder()
 *         .animations(new NpcAnimations("", "wave", "nod", Duration.ofSeconds(20),
 *                 "wave", 4.0, Duration.ofSeconds(30)))
 *         .build();
 * }</pre>
 *
 * <p>A config record: nest it in a plugin's own record and the file gets these keys,
 * each animation written in {@link NpcAnimation}'s format ({@code wave},
 * {@code swing, 250ms, offhand}) or, since 1.302.0, as a whole-body
 * {@link NpcPerformance} ({@code anim:wave}, {@code emote:bow},
 * {@code follow:2;keys:...}): a value with a colon is a performance. Empty means none.
 *
 * <pre>{@code
 * left-click: ''
 * right-click: wave
 * idle: nod
 * idle-every: 20s
 * greet: wave
 * greet-range: 4.0
 * greet-cooldown: 30s
 * perform-range: 24.0
 * }</pre>
 *
 * <p>Who sees what: a click and an idle animation are played for everyone who sees the
 * NPC; a greeting only for the player it greets, so a crowd walking up one by one does
 * not make it wave at nobody for everyone else.
 *
 * @param leftClick     played when a viewer left-clicks it
 * @param rightClick    played when a viewer right-clicks it
 * @param idle          played every {@code idleEvery}, while anyone sees it
 * @param idleEvery     how often the idle animation plays; zero for never
 * @param greet         played for a player who comes within {@code greetRange}
 * @param greetRange    how close a player has to come to be greeted, in blocks
 * @param greetCooldown how long before the same player is greeted again
 * @param performRange  how close a player has to be to be shown a performance, in blocks;
 *                      an idle performance with nobody this close is skipped (since 1.302.0)
 * @since 1.300.0
 */
public record NpcAnimations(
        @Comment("Played when a player left-clicks it, for everyone who sees it. Presets: wave,")
        @Comment("wave-offhand, nod, bow, flinch, punch, cheer; or moves joined by commas: swing,")
        @Comment("offhand, hurt, crouch, stand and waits like 250ms. Or a whole-body performance:")
        @Comment("anim:<name>, emote:<id>, or [RAGDOLL] options ending in keys:<frames>. Empty for none.")
        String leftClick,
        @Comment("Played when a player right-clicks it. Same format.")
        String rightClick,
        @Comment("Played on its own every idle-every, while anyone sees it. Same format.")
        String idle,
        @Comment("How often the idle animation plays, such as 20s. 0 for never.")
        Duration idleEvery,
        @Comment("Played for a player who walks up to it, seen only by that player. Same format.")
        String greet,
        @Comment("How close a player has to come to be greeted, in blocks.")
        double greetRange,
        @Comment("How long before the same player is greeted again, such as 30s.")
        Duration greetCooldown,
        @Comment("Performances (anim:, emote:, keys:) play only for players this close, in blocks;")
        @Comment("an idle performance with nobody this close is skipped.")
        double performRange) {

    /** How close a player has to be to be shown a performance, unless a file says otherwise. */
    public static final double DEFAULT_PERFORM_RANGE = 24.0;

    private static final NpcAnimations NONE = new NpcAnimations();

    /** None at all: the defaults a file is written with. */
    public NpcAnimations() {
        this("", "", "", Duration.ofSeconds(20), "", 4.0, Duration.ofSeconds(30));
    }

    /** The triggers without a perform range of their own: {@value #DEFAULT_PERFORM_RANGE} blocks. */
    public NpcAnimations(String leftClick, String rightClick, String idle, Duration idleEvery,
                         String greet, double greetRange, Duration greetCooldown) {
        this(leftClick, rightClick, idle, idleEvery, greet, greetRange, greetCooldown,
                DEFAULT_PERFORM_RANGE);
    }

    /** Reads what a file left out or got wrong as the default, never as a failure. */
    public NpcAnimations {
        if (leftClick == null) leftClick = "";
        if (rightClick == null) rightClick = "";
        if (idle == null) idle = "";
        if (idleEvery == null || idleEvery.isNegative()) idleEvery = Duration.ZERO;
        if (greet == null) greet = "";
        if (greetRange < 0) greetRange = 0;
        if (greetCooldown == null || greetCooldown.isNegative()) greetCooldown = Duration.ZERO;
        if (!(performRange > 0)) performRange = DEFAULT_PERFORM_RANGE;
    }

    /** No animation on any trigger. */
    public static @NotNull NpcAnimations none() {
        return NONE;
    }

    /** Whether no trigger plays anything, so the NPC costs nothing extra. */
    public boolean isEmpty() {
        return leftClick.isBlank() && rightClick.isBlank() && idle.isBlank() && greet.isBlank();
    }
}
