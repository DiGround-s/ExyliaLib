package net.exylia.lib.api.practicebot;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * What to spawn.
 *
 * <p>A bot spawned from here is the caller's bot, not a copy of the server's
 * {@code /bot}. The two things it needs from you are a way of fighting and a
 * level of skill; everything else has an answer already and can be left alone.
 * When one of those answers is wrong for what you are building, say so - and say
 * only that one:
 *
 * <ul>
 *   <li>{@link #kit} - the gear, when the fight is supposed to be a kit your own
 *       plugin built rather than the mode's idea of one.</li>
 *   <li>{@link #limits} - how far it will go for the fight, when the geometry is
 *       an arena rather than a player's feet.</li>
 *   <li>{@link #tuning} - health, totems, knockback, terrain damage and the rest
 *       of what would otherwise be the server admin's sandbox settings.</li>
 *   <li>{@link #settingsMenu} - whether its owner can crouch-click it open and
 *       change it mid-fight.</li>
 * </ul>
 *
 * <p>Built with {@link #builder(Player, Location)} for anything past the two
 * required answers:
 *
 * <pre>{@code
 * BotSpec spec = BotSpec.builder(owner, arenaSpawn)
 *         .mode(CombatMode.CRYSTAL_PVP)
 *         .difficulty(Difficulty.HARD)
 *         .name("Crystal Bot")
 *         .kit(BotKit.of(arenaKit))
 *         .limits(BotLimits.unlimited())
 *         .tuning(BotTuning.builder().breakBlocks(false).build())
 *         .settingsMenu(false)
 *         .build();
 * }</pre>
 *
 * @param owner        whose bot this is. Answers to nothing about combat - see
 *                     {@link BotHandle#setTarget(Player)} for that - but does
 *                     decide who gets told when it respawns, and who has to be
 *                     online for it to exist. A player may own several bots at
 *                     once, and a bot spawned here never displaces the one they
 *                     spawned themselves with {@code /bot}
 * @param spawn        where it appears. A duel wants the far spawn, not the
 *                     player's feet
 * @param mode         how it fights, and therefore what it carries
 * @param difficulty   how well it fights
 * @param respawn      whether the bot comes back on its own after dying. False
 *                     for anything running its own match: a match decides when
 *                     the fight is over, and a bot that quietly reappears
 *                     mid-cleanup is a second fight nobody started
 * @param name         what to call it above its head, or null for the plugin's
 *                     own configured name. Worth setting for anything the owner
 *                     is fighting rather than practising on: the default names a
 *                     bot after its owner, which in a duel means two of you
 * @param skin         whose skin it wears, by player name, or null for the
 *                     plugin's configured one
 * @param kit          what it fights with, in a player's own inventory layout,
 *                     or null to let the mode dress it. The bot works out which
 *                     of those items it can hold and which it can spend; a kit
 *                     with nothing it can use is a bot that fights with its
 *                     fists. A kit is also a ledger: a bot sent in with one
 *                     spends it and runs out of it, crystals, anchors, pearls,
 *                     potions and totems included
 * @param limits       how far it will go for the fight, or null for the plugin's
 *                     own configured distances. A match across an arena wants
 *                     {@link BotLimits#unlimited()}: the sandbox figures assume
 *                     a dummy standing next to its owner
 * @param tuning       what to change about how the bot is built, or null to
 *                     take the defaults. A bot spawned here starts from a
 *                     vanilla body - twenty health, killable, knocked back like
 *                     a player, no totems beyond the ones its kit carries - and
 *                     from the server's configuration for everything that is
 *                     genuinely tuning rather than a sandbox cheat. This is how
 *                     you change either
 * @param settingsMenu whether the owner may crouch right-click the bot to open
 *                     its settings menu. True is the plugin's own behaviour and
 *                     right for a sandbox dummy; false for anything you are
 *                     running as a match, where a player editing their opponent
 *                     mid-fight is not a feature
 *
 * @since 1.73.0
 */
public record BotSpec(
        Player owner,
        Location spawn,
        CombatMode mode,
        Difficulty difficulty,
        boolean respawn,
        String name,
        String skin,
        BotKit kit,
        BotLimits limits,
        BotTuning tuning,
        boolean settingsMenu) {

    public BotSpec {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(spawn, "spawn");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(difficulty, "difficulty");
        if (spawn.getWorld() == null) {
            throw new IllegalArgumentException("spawn location has no world");
        }
    }

    /**
     * The nine-argument shape from before tuning existed.
     *
     * @deprecated use {@link #builder(Player, Location)}. Kept so integrations
     *             written against 1.86 still compile; it leaves the tuning unset
     *             and the settings menu open, which is what they got.
     */
    @Deprecated(since = "1.127.0")
    public BotSpec(Player owner, Location spawn, CombatMode mode, Difficulty difficulty, boolean respawn,
                   String name, String skin, BotKit kit, BotLimits limits) {
        this(owner, spawn, mode, difficulty, respawn, name, skin, kit, limits, null, true);
    }

    /** A bot dressed however the plugin is configured to dress them. */
    public BotSpec(Player owner, Location spawn, CombatMode mode, Difficulty difficulty, boolean respawn) {
        this(owner, spawn, mode, difficulty, respawn, null, null, null, null, null, true);
    }

    /** Named and skinned, still dressed by its mode. */
    public BotSpec(Player owner, Location spawn, CombatMode mode, Difficulty difficulty, boolean respawn,
                   String name, String skin) {
        this(owner, spawn, mode, difficulty, respawn, name, skin, null, null, null, true);
    }

    /** The same bot, fighting with a kit of the caller's choosing. */
    public BotSpec withKit(BotKit value) {
        return new BotSpec(owner, spawn, mode, difficulty, respawn, name, skin, value, limits,
                tuning, settingsMenu);
    }

    /** The same bot, fighting under distances the caller decides. */
    public BotSpec withLimits(BotLimits value) {
        return new BotSpec(owner, spawn, mode, difficulty, respawn, name, skin, kit, value,
                tuning, settingsMenu);
    }

    /** The same bot, built to the caller's numbers rather than the server's. */
    public BotSpec withTuning(BotTuning value) {
        return new BotSpec(owner, spawn, mode, difficulty, respawn, name, skin, kit, limits,
                value, settingsMenu);
    }

    /** The same bot, with its settings menu opened or shut to its owner. */
    public BotSpec withSettingsMenu(boolean value) {
        return new BotSpec(owner, spawn, mode, difficulty, respawn, name, skin, kit, limits,
                tuning, value);
    }

    /** A bot that fights the player who asked for it and does not come back. */
    public static BotSpec duel(Player owner, Location spawn, CombatMode mode, Difficulty difficulty) {
        return new BotSpec(owner, spawn, mode, difficulty, false);
    }

    /** The same, called something of its own so a duel is not you against you. */
    public static BotSpec duel(Player owner, Location spawn, CombatMode mode, Difficulty difficulty,
                               String name, String skin) {
        return builder(owner, spawn).mode(mode).difficulty(difficulty).name(name).skin(skin).build();
    }

    /**
     * A spec under construction.
     *
     * <p>Only the owner and the spawn have no sensible default, so they are the
     * arguments; a bot built and never told anything else is a plain
     * {@link CombatMode#SWORD} opponent at {@link Difficulty#NORMAL} that does
     * not come back.
     */
    public static Builder builder(Player owner, Location spawn) {
        return new Builder(owner, spawn);
    }

    /** This spec, ready to be changed into another one. */
    public Builder toBuilder() {
        Builder builder = new Builder(owner, spawn);
        builder.mode = mode;
        builder.difficulty = difficulty;
        builder.respawn = respawn;
        builder.name = name;
        builder.skin = skin;
        builder.kit = kit;
        builder.limits = limits;
        builder.tuning = tuning;
        builder.settingsMenu = settingsMenu;
        return builder;
    }

    public static final class Builder {

        private final Player owner;
        private final Location spawn;
        private CombatMode mode = CombatMode.SWORD;
        private Difficulty difficulty = Difficulty.NORMAL;
        private boolean respawn;
        private String name;
        private String skin;
        private BotKit kit;
        private BotLimits limits;
        private BotTuning tuning;
        private boolean settingsMenu = true;

        private Builder(Player owner, Location spawn) {
            this.owner = owner;
            this.spawn = spawn;
        }

        public Builder mode(CombatMode value) {
            this.mode = value;
            return this;
        }

        public Builder difficulty(Difficulty value) {
            this.difficulty = value;
            return this;
        }

        /** Whether it comes back on its own after dying. Off unless you say so. */
        public Builder respawn(boolean value) {
            this.respawn = value;
            return this;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder skin(String value) {
            this.skin = value;
            return this;
        }

        public Builder kit(BotKit value) {
            this.kit = value;
            return this;
        }

        public Builder limits(BotLimits value) {
            this.limits = value;
            return this;
        }

        public Builder tuning(BotTuning value) {
            this.tuning = value;
            return this;
        }

        /** Whether the owner may crouch-click it open. True unless you say so. */
        public Builder settingsMenu(boolean value) {
            this.settingsMenu = value;
            return this;
        }

        /**
         * A match opponent, in one call.
         *
         * <p>Shorthand for the three answers every practice match gives: the
         * arena's own kit, no distance deciding anything, and no menu to open
         * mid-round. The bot is already built on a vanilla body rather than the
         * server's sandbox, so nothing has to be said about that.
         *
         * @param value the kit both sides are fighting, or null to let the mode
         *              dress it
         */
        public Builder match(BotKit value) {
            return kit(value)
                    .limits(BotLimits.unlimited())
                    .settingsMenu(false)
                    .respawn(false);
        }

        public BotSpec build() {
            return new BotSpec(owner, spawn, mode, difficulty, respawn, name, skin, kit, limits,
                    tuning, settingsMenu);
        }
    }
}
