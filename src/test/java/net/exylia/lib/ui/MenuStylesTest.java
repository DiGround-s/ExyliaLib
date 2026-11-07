package net.exylia.lib.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MenuStylesTest {

    @Test
    void firstRuleThatAnswersWins() {
        MenuStyle c = MenuStyle.CLASSIC, s = MenuStyle.SCREEN;
        assertEquals(c, MenuStyles.decide(false, s, s, s, s));     // a client that cannot draw screens
        assertEquals(c, MenuStyles.decide(true, c, s, s, s));      // the player's own choice
        assertEquals(s, MenuStyles.decide(true, null, s, c, c));   // the plugin's config
        assertEquals(s, MenuStyles.decide(true, null, null, s, c)); // screens.yml plugins.<name>
        assertEquals(s, MenuStyles.decide(true, null, null, null, s));
        assertEquals(c, MenuStyles.decide(true, null, null, null, null));
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
