package net.exylia.lib.text;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A placeholder written inside a tag is part of the tag, such as {@code <head:%player_name%>}. */
class TagArgumentTest {

    @Test
    void aValueInsideATagGoesInBeforeTheParse() {
        assertEquals("<head:Steve> %player_name%",
                Text.inlineInTags("<head:%player_name%> %player_name%", "%player_name%", "Steve"));
    }

    @Test
    void aValueCannotCloseTheTag() {
        assertEquals("<head:Stevered>", Text.inlineInTags("<head:%p%>", "%p%", "Steve><red>"));
    }

    @Test
    void clickAndHoverKeepTheirMarkers() {
        String source = "<click:run_command:/warp %p%><hover:show_text:'%p%'>go";
        assertEquals(source, Text.inlineInTags(source, "%p%", "spawn"));
    }

    @Test
    void aTagArgumentResolvesPerViewer() {
        var component = Text.component("<color:%c%>hi %c%", List.of("%c%", "red"));
        assertEquals("hi red", PlainTextComponentSerializer.plainText().serialize(component));
        String styled = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(component);
        org.junit.jupiter.api.Assertions.assertTrue(styled.contains("red"), styled);
    }
}
