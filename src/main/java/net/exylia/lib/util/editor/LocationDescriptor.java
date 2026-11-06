package net.exylia.lib.util.editor;

import net.exylia.lib.text.Lore;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.util.teleport.ExyliaLocation;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * How a place draws and edits itself in a list.
 *
 * <p>Handed to the list editor by {@link PluginEditors#locations}. Spawn points,
 * arena corners, warp targets — every plugin in the ecosystem keeps a list of
 * these and every one of them wrote its own screen for it.
 *
 * <h2>Adding and editing both mean "where I am standing"</h2>
 * Nobody types coordinates. Adding takes the viewer's own position, and so does
 * editing a row: an admin fixes a spawn point by standing where it should be and
 * clicking it, which is one action instead of three numbers and a guess about
 * which way {@code yaw} counts.
 *
 * @since 1.56.0
 */
final class LocationDescriptor implements EditorDescriptor<ExyliaLocation> {

    /** An action line in these cards' tone. */
    private static final String ACT = Lore.Tone.PRIMARY.colour() + "{icon:click} ";

    /** The clipboard bucket location lists share. */
    static final String TYPE_KEY = "exylia:locations";

    private final Plugin plugin;

    LocationDescriptor(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public @NotNull String label(@NotNull ExyliaLocation entry) {
        return Lore.name(Lore.Tone.PRIMARY, Phrases.tr("World"), entry.world());
    }

    @Override
    public @NotNull String icon(@NotNull ExyliaLocation entry) {
        return entry.isLocal() ? "ENDER_PEARL" : "ENDER_EYE";
    }

    @Override
    public @NotNull List<String> lore(@NotNull ExyliaLocation entry) {
        List<String> lore = new java.util.ArrayList<>(6);
        lore.add(Lore.Tone.PRIMARY.colour() + "✦ " + Phrases.tr("Place"));
        if (!entry.isLocal()) {
            lore.add(Phrases.tr("  {letters_black}· {letters}Server{letters_black}: {info}{0}", entry.server()));
        }
        lore.add(Phrases.tr("  {letters_black}· {letters}World{letters_black}: {info}{0}", entry.world()));
        lore.add(Phrases.tr("  {letters_black}· {letters}At{letters_black}: {info}{0}{letters_black}, {info}{1}{letters_black}, {info}{2}",
                round(entry.x()), round(entry.y()), round(entry.z())));
        lore.add("");
        lore.add(ACT + Phrases.tr("Editing moves it to where you stand"));
        return lore;
    }

    @Override
    public @NotNull ExyliaLocation create() {
        throw new IllegalStateException("a place is always taken from somebody standing in it");
    }

    @Override
    public @NotNull CompletionStage<Optional<ExyliaLocation>> create(@NotNull Player viewer) {
        return here(viewer);
    }

    @Override
    public @NotNull ExyliaLocation copy(@NotNull ExyliaLocation entry) {
        // A record is a value: copying one is the same value again, and two rows
        // holding it are two rows because the list says so, not the object.
        return new ExyliaLocation(entry.server(), entry.world(),
                entry.x(), entry.y(), entry.z(), entry.yaw(), entry.pitch());
    }

    @Override
    public @NotNull String typeKey() {
        return TYPE_KEY;
    }

    @Override
    public @NotNull CompletionStage<Optional<ExyliaLocation>> edit(@NotNull Player viewer,
                                                                   @NotNull ExyliaLocation entry) {
        return here(viewer);
    }

    private static CompletionStage<Optional<ExyliaLocation>> here(Player viewer) {
        Location standing = viewer.getLocation();
        if (standing == null || standing.getWorld() == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return CompletableFuture.completedFuture(Optional.of(ExyliaLocation.of(standing)));
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value));
    }
}
