package net.exylia.lib.camera.internal;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBossBar.Action;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a client's boss bars are, followed from the packets, so a hidden HUD comes back as it is then. */
class HudShadeTest {

    @Test
    @DisplayName("updates fold into the bar that was added, and a removed bar is not given back")
    void follows() {
        UUID player = UUID.randomUUID();
        UUID bar = UUID.randomUUID();
        HudShade.follow(player, bar, Action.ADD, Component.text("Step 1"), 0.2f, BossBar.Color.PURPLE,
                BossBar.Overlay.PROGRESS, EnumSet.noneOf(BossBar.Flag.class));
        HudShade.follow(player, bar, Action.UPDATE_HEALTH, null, 0.8f, null, null, null);
        HudShade.follow(player, bar, Action.UPDATE_TITLE, Component.text("Step 2"), 0, null, null, null);
        HudShade.follow(player, UUID.randomUUID(), Action.UPDATE_HEALTH, null, 0.5f, null, null, null);
        HudShade.Bar known = HudShade.bars(player).get(bar);
        assertEquals(0.8f, known.health, 1e-6);
        assertEquals(Component.text("Step 2"), known.title);
        assertEquals(BossBar.Color.PURPLE, known.color);
        assertEquals(1, HudShade.bars(player).size());
        assertFalse(HudShade.hidden(player));
        HudShade.follow(player, bar, Action.REMOVE, null, 0, null, null, null);
        assertTrue(HudShade.bars(player).isEmpty());
        HudShade.forget(player);
    }
}
