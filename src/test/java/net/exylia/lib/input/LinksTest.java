package net.exylia.lib.input;

import net.exylia.lib.input.internal.DialogTransport.Link;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinksTest {

    private static String plain(Component text) {
        return PlainTextComponentSerializer.plainText().serialize(text);
    }

    @Test
    void readsEveryLinkOfALineOnceWithItsWords() {
        Component line = MiniMessage.miniMessage().deserialize(
                "Discord: <click:open_url:'https://dc.exylia.net'><aqua>dc.<b>exylia</b>.net</aqua></click>"
                        + " code: <click:copy_to_clipboard:'833tepyf3a'>833tepyf3a</click>"
                        + " again <click:open_url:'https://dc.exylia.net'>here</click>"
                        + " <hover:show_text:'x'>no click</hover>");
        List<Link> links = Links.found(line);
        assertEquals(2, links.size());
        assertEquals(Link.Kind.OPEN_URL, links.get(0).kind());
        assertEquals("https://dc.exylia.net", links.get(0).value());
        assertEquals("dc.exylia.net", plain(links.get(0).label()));
        assertEquals(Link.Kind.COPY, links.get(1).kind());
        assertEquals("833tepyf3a", plain(links.get(1).label()));
    }

    @Test
    void aLineWithoutClicksHasNone() {
        assertTrue(Links.found(Component.text("hello")).isEmpty());
    }

    @Test
    void addressesGetASchemeAndReadWithout() {
        assertEquals("https://dc.exylia.net", Links.address(" dc.exylia.net "));
        assertEquals("http://a.b/c", Links.address("http://a.b/c"));
        assertEquals("dc.exylia.net", Links.shown("https://dc.exylia.net/"));
    }
}
