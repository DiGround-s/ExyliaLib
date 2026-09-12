package net.exylia.lib.text;

import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A text sent through a channel arrives as the same text.
 *
 * <p>A clan broadcast is built on the server where something happened and
 * read on every server its members are on, so what travels has to be the
 * template and its values rather than a rendered line: the receiving server
 * owns the prefix, the palette and the sound.
 */
class WireTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Prefixes.releaseAll();
        plugin = FakeServer.newPlugin("ExyliaClans");
    }

    @AfterEach
    void tearDown() {
        Prefixes.releaseAll();
        FakeServer.reset();
    }

    @Test
    @DisplayName("the template, every value and how it is inserted survive the trip")
    void valuesSurvive() {
        Prefixes.set(plugin, "CLANS >");
        Text sent = Text.from(plugin, "%prefix% %player% joined %clan% | %rank%")
                .with("%player%", "&cSteve")
                .withFormatted("%clan%", "&aExylia")
                .with("%rank%", "1|2");

        Text received = Text.fromWire(plugin, sent.wire());

        assertEquals(sent.plain(), received.plain());
        assertEquals("CLANS > &cSteve joined Exylia | 1|2", received.plain());
        assertEquals(sent.legacy(), received.legacy(), "a formatted value must still be parsed");
    }

    @Test
    @DisplayName("a string that is not a wire is a raw template")
    void aPlainStringIsATemplate() {
        assertEquals("hello", Text.fromWire(plugin, "hello").plain());
    }
}
