package net.exylia.lib.command.lamp;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** One argument up to the next space, colon and all, the way Lamp reads it. */
class KeyArgumentTest {

    private static String read(StringReader reader) throws CommandSyntaxException {
        return new KeyArgument().parse(reader);
    }

    @Test
    @DisplayName("a bare key keeps its colon and stops at the space")
    void bareKey() throws CommandSyntaxException {
        StringReader reader = new StringReader("tag:heart 7d");
        assertEquals("tag:heart", read(reader));
        assertEquals(' ', reader.peek());
    }

    @Test
    @DisplayName("a quoted key still reads, without its quotes")
    void quotedKey() throws CommandSyntaxException {
        StringReader reader = new StringReader("\"Tag:MVP\" --note x");
        assertEquals("Tag:MVP", read(reader));
        assertEquals(' ', reader.peek());
    }

    @Test
    @DisplayName("the last argument reads to the end")
    void lastArgument() throws CommandSyntaxException {
        StringReader reader = new StringReader("nick_color:aurora");
        assertEquals("nick_color:aurora", read(reader));
        assertEquals(false, reader.canRead());
    }

    @Test
    @DisplayName("an unclosed quote is refused, as Brigadier's own string is")
    void unclosedQuote() {
        assertThrows(CommandSyntaxException.class, () -> read(new StringReader("\"tag:heart")));
    }

    @Test
    @DisplayName("a placeholder token keeps its percent signs and semicolons")
    void placeholderToken() throws CommandSyntaxException {
        StringReader reader = new StringReader("%topper_jump;top_name;1% Steve");
        assertEquals("%topper_jump;top_name;1%", new TokenArgument().parse(reader));
        assertEquals(' ', reader.peek());
    }
}
