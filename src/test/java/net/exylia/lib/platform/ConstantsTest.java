package net.exylia.lib.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConstantsTest {

    /** The shape of Sound on 1.21.1. */
    enum OldSound { ENTITY_PLAYER_LEVELUP, UI_BUTTON_CLICK }

    /** The shape of Sound from 1.21.3: an interface with static constants and no valueOf. */
    interface NewSound {
        NewSound ENTITY_PLAYER_LEVELUP = new NewSound() { };
        NewSound UI_BUTTON_CLICK = new NewSound() { };
    }

    @Test
    void readsAnEnum() {
        assertSame(OldSound.UI_BUTTON_CLICK, Constants.named(OldSound.class, "ui_button_click"));
        assertSame(OldSound.ENTITY_PLAYER_LEVELUP, Constants.named(OldSound.class, " ENTITY_PLAYER_LEVELUP "));
    }

    @Test
    void readsAnInterfaceThroughItsFields() {
        assertSame(NewSound.UI_BUTTON_CLICK, Constants.named(NewSound.class, "UI_BUTTON_CLICK"));
    }

    @Test
    void anUnknownNameIsNull() {
        assertNull(Constants.named(OldSound.class, "NOT_A_SOUND"));
        assertNull(Constants.named(NewSound.class, "NOT_A_SOUND"));
    }
}
