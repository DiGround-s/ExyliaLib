package net.exylia.lib.practicebot;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Objects;

/**
 * What to spawn.
 *
 * <p>Deliberately small. The bot has around thirty settings a player can tune
 * from its own menu, and none of them belong in a contract with another plugin:
 * an integration asks for a way of fighting at a level of skill, and the bot
 * decides how well that is executed.
 *
 * <p>The gear is the one thing an integration may have a better answer for than
 * the bot does. A practice match is two sides fighting the same kit, and which
 * kit that is belongs to whoever built the arena, not to the bot's own idea of
 * how the mode should be equipped. So {@link #kit} is optional and it overrides:
 * sent, the bot fights with it; left null, the bot dresses itself the way the
 * mode says, which is what a sandbox {@code /bot} wants.
 *
 * @param owner      whose bot this is. Answers to nothing about combat - see
 *                   {@link BotHandle#setTarget(Player)} for that - but does
 *                   decide who gets told when it respawns, and who has to be
 *                   online for it to exist
 * @param spawn      where it appears. A duel wants the far spawn, not the
 *                   player's feet
 * @param mode       how it fights, and therefore what it carries
 * @param difficulty how well it fights
 * @param respawn    whether the bot comes back on its own after dying. False for
 *                   anything running its own match: a match decides when the
 *                   fight is over, and a bot that quietly reappears mid-cleanup
 *                   is a second fight nobody started
 * @param name       what to call it above its head, or null for the plugin's own
 *                   configured name. Worth setting for anything the owner is
 *                   fighting rather than practising on: the default names a bot
 *                   after its owner, which in a duel means two of you
 * @param skin       whose skin it wears, by player name, or null for the
 *                   plugin's configured one
 * @param kit        what it fights with, in a player's own inventory layout, or
 *                   null to let the mode dress it. The bot works out which of
 *                   those items it can hold and which it can spend; a kit with
 *                   nothing it can use is a bot that fights with its fists
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
        BotKit kit) {

    public BotSpec {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(spawn, "spawn");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(difficulty, "difficulty");
        if (spawn.getWorld() == null) {
            throw new IllegalArgumentException("spawn location has no world");
        }
    }

    /** A bot dressed however the plugin is configured to dress them. */
    public BotSpec(Player owner, Location spawn, CombatMode mode, Difficulty difficulty, boolean respawn) {
        this(owner, spawn, mode, difficulty, respawn, null, null, null);
    }

    /** Named and skinned, still dressed by its mode. */
    public BotSpec(Player owner, Location spawn, CombatMode mode, Difficulty difficulty, boolean respawn,
                   String name, String skin) {
        this(owner, spawn, mode, difficulty, respawn, name, skin, null);
    }

    /** The same bot, fighting with a kit of the caller's choosing. */
    public BotSpec withKit(BotKit value) {
        return new BotSpec(owner, spawn, mode, difficulty, respawn, name, skin, value);
    }

    /** A bot that fights the player who asked for it and does not come back. */
    public static BotSpec duel(Player owner, Location spawn, CombatMode mode, Difficulty difficulty) {
        return new BotSpec(owner, spawn, mode, difficulty, false);
    }

    /** The same, called something of its own so a duel is not you against you. */
    public static BotSpec duel(Player owner, Location spawn, CombatMode mode, Difficulty difficulty,
                               String name, String skin) {
        return new BotSpec(owner, spawn, mode, difficulty, false, name, skin, null);
    }
}
