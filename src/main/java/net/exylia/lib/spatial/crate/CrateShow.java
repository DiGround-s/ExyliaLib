package net.exylia.lib.spatial.crate;

import net.exylia.lib.spatial.SpatialPreview;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One opening, as the caller already decided it: the prizes are drawn and paid for before the scene
 * starts, and the scene only shows them. Every method is called on the viewer's thread.
 *
 * <p>The crate module ({@code CrateOpening.SPATIAL}) is the one caller most plugins need; a plugin with
 * its own lottery hands its own.
 *
 * @since 1.416.0
 */
public interface CrateShow {

    /** What the reel may show going past. Empty: only the prizes go past. */
    @NotNull List<CrateFace> faces();

    /** The prizes, in the order they are revealed; never empty. */
    @NotNull List<CrateFace> prizes();

    /**
     * Hands prize {@code index} over: the moment its reel lands, or at once when the player skips. Asked
     * at most once per prize by the scene; must itself never hand a prize over twice.
     *
     * @return what became of it
     */
    @NotNull CrateReveal reveal(int index);

    /** How prize {@code index} is tried on once revealed; {@code null} for its icon alone. */
    @Nullable SpatialPreview preview(int index);

    /** Whether an effect preview's mannequin is taken down while it plays. */
    default boolean previewVanishes() {
        return true;
    }

    /** Whether "open another" is offered after the last reveal: the player can pay for one more. */
    boolean canOpenAnother();

    /** The player pressed "open another"; the scene is already closing. */
    void openAnother();

    /**
     * The scene is over. Every prize was revealed or handed over by then, except when {@code quit}: a
     * player leaving the server is paid by the caller's own quit path, so nothing is asked of a player
     * on their way out.
     */
    void ended(boolean quit);
}
