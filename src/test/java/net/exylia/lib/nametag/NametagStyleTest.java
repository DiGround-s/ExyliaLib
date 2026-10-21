package net.exylia.lib.nametag;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NametagStyleTest {

    @Test
    void textCarryingStylesGetTheirOwnShortTeam() {
        NametagStyle plain = NametagStyle.of(NamedTextColor.RED);
        NametagStyle prefixed = plain.withPrefix(Component.text("[PVP] "));
        assertEquals("exy____" + Integer.toHexString(NamedTextColor.RED.value()), plain.teamName());
        assertNotEquals(plain.teamName(), prefixed.teamName());
        assertTrue(prefixed.teamName().length() <= 16);
        assertEquals(prefixed.teamName(), plain.withPrefix(Component.text("[PVP] ")).teamName());
        assertNotEquals(prefixed.teamName(), prefixed.withSuffix(Component.text("!")).teamName());
        assertNull(NametagStyle.glowOnly().teamName());
        assertTrue(NametagStyle.glowOnly().withSuffix(Component.text("x")).needsTeam());
    }
}
