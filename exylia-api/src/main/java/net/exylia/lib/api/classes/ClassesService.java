package net.exylia.lib.api.classes;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading and driving ExyliaClasses.
 *
 * <pre>{@code
 * ExyliaAPI.get(ClassesService.class).ifPresent(classes ->
 *     classes.classOf(player.getUniqueId())
 *            .ifPresent(found -> player.sendMessage("Class: " + found.name())));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaClasses enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "classes are not part of this server" rather than
 * as a failure.
 *
 * <h2>A class is worn, not chosen</h2>
 * A player is in a class because their armor says so. Nothing here assigns one
 * directly: {@link #apply} asks the plugin to enter a class the way equipping
 * the armor would, warm-up and refusal messages included, and the class ends by
 * itself the moment a piece comes off. A plugin that wants somebody in a class
 * gives them the armor {@link PlayerClass#equipment()} names.
 *
 * <h2>Queries are cheap, actions are not</h2>
 * Everything that returns a value reads from the plugin's cache and is safe to
 * call from a menu redraw or a placeholder. {@link #apply} and {@link #remove}
 * run the same flow the player's own gear change runs — potion effects,
 * cooldowns, bars, the messages they see — so call those on the main thread.
 *
 * @since 1.0.0
 */
public interface ClassesService {

    // ── The catalogue ──────────────────────────────────────────────────────

    /**
     * Every class the server declares.
     *
     * <p>A snapshot of the catalogue. Fine for building a menu, wasteful in a
     * loop.
     *
     * @return all classes
     */
    @NotNull
    @Unmodifiable
    List<PlayerClass> classes();

    /**
     * A class by id.
     *
     * @param classId the class id
     * @return the class, or empty when the catalogue declares no such id
     */
    @NotNull
    Optional<PlayerClass> classById(@NotNull String classId);

    /**
     * Whether the catalogue declares a class.
     *
     * @param classId the class id
     * @return {@code true} when it exists
     */
    boolean exists(@NotNull String classId);

    /**
     * What a class can do.
     *
     * @param classId the class id
     * @return its abilities, empty when the class does not exist or has none
     */
    @NotNull
    @Unmodifiable
    List<ClassAbility> abilities(@NotNull String classId);

    /**
     * The resource a class spends.
     *
     * @param classId the class id
     * @return its energy, or empty when the class does not exist or spends
     *         nothing
     */
    @NotNull
    Optional<ClassEnergy> energy(@NotNull String classId);

    // ── Permissions ────────────────────────────────────────────────────────

    /**
     * Whether a player may enter a class.
     *
     * <p>Answers by the server's own rule, so a class the owner left open is
     * allowed for everybody rather than refused for want of a node nobody
     * declared.
     *
     * @param player  the player
     * @param classId the class id
     * @return {@code true} when they may enter it; {@code false} when the class
     *         does not exist
     */
    boolean canUse(@NotNull Player player, @NotNull String classId);

    // ── What a player is ───────────────────────────────────────────────────

    /**
     * Whether a player is in a class right now.
     *
     * @param player the player
     * @return {@code true} when they are
     */
    boolean isInClass(@NotNull UUID player);

    /**
     * The class a player is in.
     *
     * @param player the player
     * @return their class, or empty when they are in none
     */
    @NotNull
    Optional<PlayerClass> classOf(@NotNull UUID player);

    /**
     * How much energy a player holds right now.
     *
     * <p>Rises on its own between calls, by the class's own refill rate. Read it
     * when you draw, rather than caching it.
     *
     * @param player the player
     * @return their energy, {@code 0} when they are in no class or their class
     *         spends none
     */
    double energyOf(@NotNull UUID player);

    /**
     * How many marks a player carries.
     *
     * <p>Marks are put on a player by somebody else's weapon and spent by it.
     * Zero is the ordinary state.
     *
     * @param player the player
     * @return the mark count
     */
    int marks(@NotNull UUID player);

    /**
     * Whether a player is marked at all.
     *
     * <p>Not the same as {@link #marks} being above zero: a mark also belongs
     * to the weapon that placed it, and one whose weapon is gone no longer
     * counts.
     *
     * @param player the player
     * @return {@code true} when they are marked
     */
    boolean isMarked(@NotNull UUID player);

    /**
     * The class a player is currently warming up into.
     *
     * <p>Between putting the armor on and the class taking effect there is a
     * delay the owner configures, and a player in it is in no class yet. A bar
     * or a scoreboard that wants to show what is coming asks here.
     *
     * @param player the player
     * @return the class they are warming up into, or empty when they are not
     *         warming up
     */
    @NotNull
    Optional<PlayerClass> warmingUp(@NotNull Player player);

    /**
     * The class a player's current armor names, whether or not they are in it.
     *
     * <p>What the plugin itself asks on every gear change. Useful for a preview
     * that shows what a set would make somebody before they wear it, and for
     * telling "wearing the wrong armor" apart from "warming up".
     *
     * @param player the player
     * @return the class their armor names, or empty when it names none
     */
    @NotNull
    Optional<PlayerClass> classForEquipment(@NotNull Player player);

    // ── Actions ────────────────────────────────────────────────────────────

    /**
     * Asks a player to enter a class.
     *
     * <p>The same flow equipping the armor runs: the permission is checked and
     * refused out loud, and the warm-up runs before the class takes effect —
     * so nothing has happened by the time this returns. It reports nothing back
     * for that reason; read {@link #classOf} afterwards if you need to know.
     *
     * <p>The class still ends the moment the player's armor stops naming it, so
     * this is not a way to put somebody in a class they are not dressed for.
     *
     * @param player  the player
     * @param classId the class id
     */
    void apply(@NotNull Player player, @NotNull String classId);

    /**
     * Takes a player out of their class.
     *
     * <p>Removes the passive effects, the bars and the cooldowns with it. The
     * player keeps their armor, so putting a piece back on will put them in the
     * class again.
     *
     * @param player the player
     */
    void remove(@NotNull Player player);

    /**
     * Uses one of a player's abilities, as right-clicking its item would.
     *
     * <p>The whole flow runs: the cooldown and the energy are checked and
     * refused out loud, {@link net.exylia.lib.api.classes.event.AbilityUseEvent}
     * is fired, the energy is spent, the cooldown starts and the effects land on
     * whoever the ability reaches. The one thing a click does that this does not
     * is consume the item — there is no item, and the player does not need to
     * hold one.
     *
     * <p>Call on the thread that owns the player.
     *
     * @param player  the player
     * @param trigger the ability's {@link ClassAbility#trigger() trigger}
     * @return what happened
     * @since 1.3.0
     */
    @NotNull
    AbilityUseResult useAbility(@NotNull Player player, @NotNull Material trigger);

    // ── Energy ─────────────────────────────────────────────────────────────
    //
    // Energy belongs to the player's class and refills every tick on the
    // thread that owns the player. Call these from that thread — a command, a
    // listener for that player, or a task scheduled on them — and the bar is
    // redrawn with the change.

    /**
     * Gives a player energy.
     *
     * <p>Never past their class's {@link ClassEnergy#max() ceiling}: whatever
     * would overflow it is lost, as it is when energy refills on its own.
     *
     * @param player the player
     * @param amount how much to give
     * @return {@code true} when it went into their pool; {@code false} when they
     *         are in no class or their class spends no energy
     * @throws IllegalArgumentException when {@code amount} is negative
     * @since 1.3.0
     */
    boolean giveEnergy(@NotNull Player player, double amount);

    /**
     * Takes energy from a player, all of it or none.
     *
     * <p>What an ability does when it is used, without the ability: a plugin
     * charging energy for something of its own spends it here, and a player
     * who cannot afford it keeps what they hold.
     *
     * @param player the player
     * @param amount how much to take
     * @return {@code true} when it was taken; {@code false} when they hold less
     *         than {@code amount}, are in no class, or their class spends no
     *         energy
     * @throws IllegalArgumentException when {@code amount} is negative
     * @since 1.3.0
     */
    boolean spendEnergy(@NotNull Player player, double amount);
}
