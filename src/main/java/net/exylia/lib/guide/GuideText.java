package net.exylia.lib.guide;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

/**
 * A text only one player sees: floating ahead of their eyes, or standing at a
 * place.
 *
 * <pre>{@code
 * GuideText panel = guides.companion(player, Text.of("{primary}&lFIND THE SMITH").build());
 * panel.text(Text.of("{primary}&lFIND THE SMITH\n{letters}Progress {info}2/3").build());
 * }</pre>
 *
 * @since 1.391.0
 */
public interface GuideText extends GuideHandle {

    /**
     * Changes what it says, in place.
     *
     * <p>One metadata packet on the player's next tick, never a respawn, so a
     * progress counter that changes every second does not flicker. A text
     * equal to the one showing sends nothing. Safe from any thread.
     *
     * @param text the new text
     */
    void text(@NotNull Component text);

    /** What it says now. */
    @NotNull Component text();
}
