package net.exylia.lib.util.preview;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;
import net.exylia.lib.config.Time;
import net.exylia.lib.util.teleport.ExyliaLocation;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Where and how a preview stage is built.
 *
 * <p>Nests inside a plugin's own configuration record like any other section,
 * so a server owner can move the stage without a code change:
 *
 * <pre>{@code
 * public record MySettings(PreviewSettings preview) {
 *     public MySettings() {
 *         this(new PreviewSettings());
 *     }
 * }
 * }</pre>
 *
 * <h2>Why the location has no default</h2>
 * A preview is shown wherever the server owner built the room for it. The
 * library cannot guess that, and guessing wrong puts the player inside terrain
 * or in an unbuilt corner of the map. So an unset {@link #location()} means the
 * plugin has no stage yet and must not preview at all; each plugin gives its
 * admins a {@code setpreviewlocation} command to fill it in.
 *
 * @param location     where the player stands during a preview, in the stored
 *                     {@link ExyliaLocation} form, or empty for none
 * @param distance     how far in front of the player the effect plays
 * @param settleTicks  how long to wait after the teleport before playing
 * @param lingerTicks  how long to keep the stage after the effect ends
 * @param maxTicks     the longest a preview may last, whatever happens
 * @param mode         whether a preview moves the player to the stage or lends
 *                     them the effect to try on a mannequin (since 1.355.0)
 * @param mannequin    the mannequins a {@link PreviewMode#MANNEQUIN} preview is
 *                     tried on (since 1.355.0)
 *
 * @since 1.30.0
 */
@Comment("Where preview effects are shown.")
@Comment("")
@Comment("A preview moves the player to the stage below, plays the effect in")
@Comment("front of them where only they can see it, then puts them back exactly")
@Comment("where they were. Until a stage is set, previews are unavailable.")
public record PreviewSettings(

        @Comment("Where the player stands while a preview plays, as")
        @Comment("server,world,x,y,z,yaw,pitch. Leave empty and set it in game")
        @Comment("with this plugin's setpreviewlocation admin command.")
        @Comment("Only the position is used: a preview always faces north, so")
        @Comment("every effect is seen from the side it was drawn for. Build")
        @Comment("the room around that, and leave the north side open.")
        @Comment("Two players previewing at once may share it: everyone is")
        @Comment("hidden from everyone, and each effect is sent to one player.")
        String location,

        @Comment("How far in front of the player the effect plays, in blocks.")
        double distance,

        @Key("settle-ticks")
        @Comment("How long to wait after the teleport before playing, so the")
        @Comment("client has the new position before the first particle.")
        @Comment("Reads a written duration too: 1s, 500ms, 2m.")
        @Time(Time.Unit.TICKS) int settleTicks,

        @Key("linger-ticks")
        @Comment("How long the stage is held after the effect finishes.")
        @Comment("Reads a written duration too: 1s, 500ms, 2m.")
        @Time(Time.Unit.TICKS) int lingerTicks,

        @Key("max-ticks")
        @Comment("The longest a preview may last. A safety net: whatever goes")
        @Comment("wrong, the player is returned after this.")
        @Comment("Reads a written duration too: 1s, 500ms, 2m.")
        @Time(Time.Unit.TICKS) int maxTicks,

        @Comment("How a preview is shown.")
        @Comment("TELEPORT  - the player is moved to the location above, shown the effect")
        @Comment("            once, and put back.")
        @Comment("MANNEQUIN - nobody is moved: the player is lent the effect for a while")
        @Comment("            and hits the mannequins below with it as often as they like.")
        @Comment("            The effect plays on the mannequins only, never in a real fight.")
        PreviewMode mode,

        Mannequin mannequin
) {

    /** Safe defaults with no stage, so a fresh install previews nothing. */
    public PreviewSettings() {
        this("", 5.0, 4, 20, 20 * 30, PreviewMode.TELEPORT, new Mannequin());
    }

    /**
     * The settings before mannequins existed: a teleport preview.
     *
     * @deprecated since 1.355.0; use the canonical constructor, which also
     *             takes the {@link PreviewMode} and the {@link Mannequin}s.
     */
    @Deprecated
    public PreviewSettings(String location, double distance, int settleTicks, int lingerTicks, int maxTicks) {
        this(location, distance, settleTicks, lingerTicks, maxTicks, PreviewMode.TELEPORT, new Mannequin());
    }

    public PreviewSettings {
        location = location == null ? "" : location.trim();
        distance = Math.max(1.0, distance);
        settleTicks = Math.max(1, settleTicks);
        lingerTicks = Math.max(0, lingerTicks);
        // The safety net cannot be shorter than the wait plus the linger, or it
        // would fire before the effect it is meant to outlast.
        maxTicks = Math.max(settleTicks + lingerTicks + 20, maxTicks);
        mode = mode == null ? PreviewMode.TELEPORT : mode;
        mannequin = mannequin == null ? new Mannequin() : mannequin;
    }

    /** The same settings with a different stage, for a command that moves it. */
    public @NotNull PreviewSettings at(@NotNull Location stage) {
        return new PreviewSettings(ExyliaLocation.of(stage).toString(),
                distance, settleTicks, lingerTicks, maxTicks, mode, mannequin);
    }

    /** The same settings shown another way. @since 1.355.0 */
    public @NotNull PreviewSettings withMode(@NotNull PreviewMode mode) {
        return new PreviewSettings(location, distance, settleTicks, lingerTicks, maxTicks, mode, mannequin);
    }

    /** The same settings with other mannequin settings. @since 1.355.0 */
    public @NotNull PreviewSettings withMannequin(@NotNull Mannequin mannequin) {
        return new PreviewSettings(location, distance, settleTicks, lingerTicks, maxTicks, mode, mannequin);
    }

    /** The same settings with the mannequins standing somewhere else. @since 1.355.0 */
    public @NotNull PreviewSettings withMannequins(@NotNull List<String> locations) {
        return withMannequin(mannequin.withLocations(locations));
    }

    /**
     * The same settings with one more mannequin, where an admin stands and
     * facing the way they face.
     *
     * @since 1.355.0
     */
    public @NotNull PreviewSettings withMannequinAt(@NotNull Location where) {
        List<String> locations = new ArrayList<>(mannequin.locations());
        locations.add(ExyliaLocation.of(where).toString());
        return withMannequins(locations);
    }

    /**
     * The mannequin closest to a spot, if one is within reach.
     *
     * @param where where to look from
     * @param reach how far away it may stand, in blocks
     * @return its entry as written in the file, or {@code null} when none is close
     * @since 1.355.0
     */
    public @Nullable String mannequinNear(@NotNull Location where, double reach) {
        String nearest = null;
        double best = reach * reach;
        for (String entry : mannequin.locations()) {
            Location at = parse(entry);
            if (at == null || !at.getWorld().equals(where.getWorld())) continue;
            double distance = at.distanceSquared(where);
            if (distance <= best) {
                best = distance;
                nearest = entry;
            }
        }
        return nearest;
    }

    /** The same settings without one mannequin, named as {@link #mannequinNear} returns it. @since 1.355.0 */
    public @NotNull PreviewSettings withoutMannequin(@NotNull String entry) {
        List<String> locations = new ArrayList<>(mannequin.locations());
        locations.remove(entry);
        return withMannequins(locations);
    }

    /**
     * Whether a preview can be shown in the mode in force: a stage for
     * {@link PreviewMode#TELEPORT}, a mannequin in a loaded world for
     * {@link PreviewMode#MANNEQUIN}.
     *
     * @since 1.355.0
     */
    public boolean ready() {
        if (mode == PreviewMode.TELEPORT) return stage() != null;
        return mannequin.locations().stream().anyMatch(entry -> parse(entry) != null);
    }

    /** A stored location as a live one, or {@code null} when it is not one or its world is not loaded. */
    static @Nullable Location parse(@NotNull String entry) {
        try {
            Location at = ExyliaLocation.fromString(entry).toBukkitLocation();
            return at == null || at.getWorld() == null ? null : at;
        } catch (RuntimeException notALocation) {
            return null;
        }
    }

    /**
     * The mannequins a {@link PreviewMode#MANNEQUIN} preview is tried on.
     *
     * @param locations   where they stand, in the stored {@link ExyliaLocation} form
     * @param trialSeconds how long a player is lent the effect, in seconds
     * @param radius      how far a player may walk from every mannequin before the
     *                    loan ends, once they have reached one
     * @param vanishOnHit whether a struck mannequin disappears for that player while
     *                    the effect plays, so a kill effect reads as a death
     * @since 1.355.0
     */
    public record Mannequin(
            @Comment("Where the mannequins stand, as server,world,x,y,z,yaw,pitch.")
            @Comment("Place them in game with this plugin's preview mannequin add admin command,")
            @Comment("which writes where you stand and the way you face.")
            List<String> locations,

            @Key("trial-seconds")
            @Comment("How long a player is lent the effect they preview.")
            @Comment("Reads a written duration too: 30s, 1m.")
            @Time(Time.Unit.SECONDS) int trialSeconds,

            @Comment("Once a player has reached a mannequin, walking further than this")
            @Comment("from all of them ends the loan early, in blocks.")
            double radius,

            @Key("vanish-on-hit")
            @Comment("Whether a struck mannequin disappears for that player while the")
            @Comment("effect plays, so a kill effect reads as a death.")
            boolean vanishOnHit,

            @Key("effect-scale")
            @Comment("How large the effect plays on a mannequin: every offset, movement, body and")
            @Comment("particle spread that many times its written size. 1 is as written.")
            double effectScale,

            @Comment("Whose skin the body that falls wears, the first source that has one:")
            @Comment("others (another online player, at random), viewer (the player trying it),")
            @Comment("defaults (vanilla's default skins, one after another).")
            List<String> skins) {

        public Mannequin() {
            this(List.of(), 30, 12.0, false, 1.0, net.exylia.lib.npc.PreviewSkins.DEFAULT_NAMES);
        }

        /** @deprecated since 1.428.0; use the constructor with {@code skins}. */
        @Deprecated
        public Mannequin(List<String> locations, int trialSeconds, double radius, boolean vanishOnHit,
                         double effectScale) {
            this(locations, trialSeconds, radius, vanishOnHit, effectScale, net.exylia.lib.npc.PreviewSkins.DEFAULT_NAMES);
        }

        /** @deprecated since 1.413.0; use the constructor with {@code effectScale}. */
        @Deprecated
        public Mannequin(List<String> locations, int trialSeconds, double radius, boolean vanishOnHit) {
            this(locations, trialSeconds, radius, vanishOnHit, 1.0, net.exylia.lib.npc.PreviewSkins.DEFAULT_NAMES);
        }

        public Mannequin {
            locations = locations == null ? List.of() : List.copyOf(locations);
            trialSeconds = Math.max(1, trialSeconds);
            radius = Math.max(2, radius);
            effectScale = effectScale > 0 && effectScale <= 16 ? effectScale : 1.0;
            skins = skins == null || skins.isEmpty() ? net.exylia.lib.npc.PreviewSkins.DEFAULT_NAMES : List.copyOf(skins);
        }

        /** {@link #skins()} as the policy that picks a body. @since 1.428.0 */
        public @NotNull net.exylia.lib.npc.PreviewSkins skinPolicy() {
            return net.exylia.lib.npc.PreviewSkins.of(skins);
        }

        /** The same settings with the mannequins standing somewhere else. */
        public @NotNull Mannequin withLocations(@NotNull List<String> locations) {
            return new Mannequin(locations, trialSeconds, radius, vanishOnHit, effectScale, skins);
        }

        /** The same settings with a struck mannequin vanishing, or not. */
        public @NotNull Mannequin withVanishOnHit(boolean vanishOnHit) {
            return new Mannequin(locations, trialSeconds, radius, vanishOnHit, effectScale, skins);
        }
    }

    /**
     * The stage as a live location.
     *
     * @return where the player is put, or {@code null} when none is configured,
     *         the stored text is not a location, or its world is not loaded
     */
    public @Nullable Location stage() {
        if (location.isBlank()) {
            return null;
        }
        try {
            return ExyliaLocation.fromString(location).toBukkitLocation();
        } catch (IllegalArgumentException notALocation) {
            return null;
        }
    }
}
