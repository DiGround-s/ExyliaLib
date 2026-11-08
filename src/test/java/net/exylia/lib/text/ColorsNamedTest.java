package net.exylia.lib.text;

import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ColorsNamedTest {

    @AfterEach
    void restore() {
        Colors.apply(new Palette());
    }

    private static Palette with(String highlight) {
        Palette base = new Palette();
        return new Palette(base.primary(), base.secondary(), base.secondaryLight(), base.letters(),
                base.lettersBlack(), base.error(), base.errorLight(), base.success(), base.successLight(),
                base.warning(), base.warningLight(), base.info(), base.infoLight(), base.accent(),
                base.neutral(), highlight, base.muted(), base.serverIp());
    }

    @Test
    void aNamedPaletteColourHasAValue() {
        Colors.apply(with("gold"));
        assertEquals(NamedTextColor.GOLD, Colors.get("highlight"));
    }

    @Test
    void aValueThatIsNoColourHasNone() {
        Colors.apply(with("not-a-colour"));
        assertNull(Colors.get("highlight"));
    }
}
