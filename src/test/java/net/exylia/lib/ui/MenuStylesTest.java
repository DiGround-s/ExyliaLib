package net.exylia.lib.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MenuStylesTest {

    @Test
    void firstRuleThatAnswersWins() {
        MenuStyle c = MenuStyle.CLASSIC, s = MenuStyle.SCREEN;
        assertEquals(c, MenuStyles.decide(false, s, s, s, s, s));     // a client that cannot draw screens
        assertEquals(s, MenuStyles.decide(true, s, c, c, c, c));      // staff override, even over the player's choice
        assertEquals(c, MenuStyles.decide(true, null, c, s, s, s));   // the player's own choice
        assertEquals(s, MenuStyles.decide(true, null, null, s, c, c));   // the plugin's config
        assertEquals(s, MenuStyles.decide(true, null, null, null, s, c)); // screens.yml plugins.<name>
        assertEquals(s, MenuStyles.decide(true, null, null, null, null, s));
        assertEquals(c, MenuStyles.decide(true, null, null, null, null, null));
    }

    @Test
    void aGameAsksItsFormOfTheStyleWithNoChestForm() {
        MenuStyle c = MenuStyle.CLASSIC, s = MenuStyle.SCREEN;
        GameForm screen = GameForm.SCREEN, world = GameForm.WORLD;
        assertEquals(world, MenuStyles.decideForm(false, screen, screen, s));   // a client that cannot draw screens
        assertEquals(screen, MenuStyles.decideForm(true, screen, world, c));    // a staff override, over the game's own
        assertEquals(world, MenuStyles.decideForm(true, null, world, s));       // a game forced to the world
        assertEquals(screen, MenuStyles.decideForm(true, null, screen, c));     // a game forced onto a screen
        assertEquals(screen, MenuStyles.decideForm(true, null, null, s));       // the player's style
        assertEquals(world, MenuStyles.decideForm(true, null, null, c));        // chest windows play in the world
        assertEquals(world, GameForm.parse("3d"));
        assertEquals(screen, GameForm.parse(" Screen "));
        assertNull(GameForm.parse("auto"));
    }

    @Test
    void parsesWhatConfigsWrite() {
        assertEquals(MenuStyle.SCREEN, MenuStyle.parse(" screen "));
        assertEquals(MenuStyle.CLASSIC, MenuStyle.parse("CLASSIC"));
        assertNull(MenuStyle.parse("DEFAULT"));
        assertNull(MenuStyle.parse(null));
        assertNull(MenuStyle.parse(""));
    }
}
