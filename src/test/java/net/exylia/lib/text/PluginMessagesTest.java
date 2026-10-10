package net.exylia.lib.text;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.effect.EffectConfig;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Values} and {@link PluginMessages}: the bag and the sender that replaced every plugin's copy. */
class PluginMessagesTest {

    private Plugin plugin;
    private PluginMessages messages;
    private FakePlayer player;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        Prefixes.releaseAll();
        plugin = FakeServer.newPlugin("ExyliaMines");
        Prefixes.set(plugin, "MINES >");
        messages = PluginMessages.of(plugin);
        player = new FakePlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        Prefixes.releaseAll();
    }

    @Test
    void sendsWithPrefixAndValues() {
        messages.send(player.player(), "%prefix% Mine %mine% reset", Values.of("mine", "gold"));
        assertEquals(List.of("MINES > Mine gold reset"), player.messages());
    }

    @Test
    void blankLineSendsNothing() {
        messages.send(player.player(), "  ", Values.of("mine", "gold"));
        messages.send(player.player(), (String) null);
        messages.send(null, "%prefix% hi");
        assertTrue(player.messages().isEmpty());
    }

    @Test
    void serverValuesAreFormattedTypedValuesAreLiteral() {
        messages.send(player.player(), "%a% %b%", Values.of("a", "&cRED").putText("b", "&cRED"));
        assertEquals(List.of("RED &cRED"), player.messages());
    }

    @Test
    void severalLinesKeepTheirOrderAndSkipBlanks() {
        messages.send(player.player(), List.of("one %n%", "", "two %n%"), Values.of("n", 1));
        assertEquals(List.of("one 1", "two 1"), player.messages());
    }

    @Test
    void plainAndNullValues() {
        assertEquals("MINES > x ", messages.plain("%prefix% x %missing%", Values.of("missing", null)));
    }

    @Test
    void applyLeavesTypedTextInert() {
        Values values = Values.of("name", "{primary}Gold").putText("typed", "<red>%player_ip%");
        String filled = values.apply("%name% by %typed%");
        assertTrue(filled.startsWith("{primary}Gold by "));
        assertEquals("Gold by <red>%player_ip%", Text.of(filled).plain());
    }

    @Test
    void mapIsTheMenuContext() {
        Values values = Values.of("id", 7).put("name", "gold");
        assertEquals(List.of("id", "name"), List.copyOf(values.map().keySet()));
        assertEquals(7, values.map().get("id"));
    }

    @Test
    void effectConfigIsFilledBeforeParsing() {
        EffectConfig effect = EffectConfig.of(new EffectConfig.Title("%mine% in %time%", "by %who%", 0, 3, 1, "auto"));
        EffectConfig filled = effect.filled(Values.of("mine", "gold").put("who", "Steve"));
        assertEquals("gold in %time%", filled.title().text());
        assertEquals("by Steve", filled.title().subtitle());
        assertTrue(filled.bossBar().isEmpty());
        assertSame(effect, effect.filled(Values.of()));
    }
}
