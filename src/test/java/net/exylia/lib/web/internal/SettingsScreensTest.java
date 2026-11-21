package net.exylia.lib.web.internal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A plugin's settings screens are read from its shipped menus and pointed at the one file that holds their keys. */
class SettingsScreensTest {

    private static final String HUB = """
            items:
              header:
                slot: 4
                material: COMPARATOR
                name: '{letters}Settings'
                lore:
                  - '{letters_black}Settings hub'
                  - ''
                  - '{primary}✎ Information'
                  - ' {letters}%s'
              ah:
                slot: 11
                material: GOLD_INGOT
                name: '{letters}Settings {letters_black}▸ <gradient:{primary}:{secondary_light}>%s</gradient>'
                lore:
                  - '{letters_black}Settings page'
                  - ''
                  - '{primary}✎ Information'
                  - ' {letters}%s'
                  - ''
                  - '{primary}✦ Status'
                  - '  {letters_black}· {info}{icon:gold} {letters}%s{letters_black}: %%ah_settings_listing_fee_percent%%'
                  - '  {letters_black}· {success}{icon:on} {letters}Module{letters_black}: %%ah_state%%'
                actions:
                  - 'market:settings_page ah'
            """;

    private static final String PAGE = """
            items:
              header:
                slot: 4
                material: GOLD_INGOT
                lore:
                  - '{letters_black}Settings page'
                  - ''
                  - '{primary}✎ Information'
                  - ' {letters}What listing costs.'
              settings-listing-fee-percent:
                slot: 12
                material: PAPER
                name: '{letters}Auction {letters_black}▸ <gradient:{primary}:{secondary_light}>%s</gradient>'
                lore:
                  - '{letters_black}Auction setting'
                  - ''
                  - '{primary}✎ Information'
                  - ' {letters}%s'
                  - ' {highlight}-1 {letters}removes it.'
                  - ''
                  - '{primary}✦ Status'
                  - '  {letters_black}· {info}{icon:gold} {letters}%s{letters_black}: %%ah_settings_listing_fee_percent%%'
                  - '%%ah_settings_listing_fee_percent_click%%'
                actions:
                  - 'market:setting ah settings.listing-fee-percent'
            """;

    private static Path lang(Path root) throws IOException {
        write(root.resolve("en/menus/admin/settings.yml"), HUB.formatted("Every setting.", "Auction house", "Fees and limits.", "Fee"));
        write(root.resolve("en/menus/admin/settings_ah.yml"), PAGE.formatted("Listing fee", "Charged to the seller.", "Fee"));
        write(root.resolve("es/menus/admin/settings.yml"), HUB.formatted("Todos los ajustes.", "Casa de subastas", "Tasas y límites.", "Tasa"));
        write(root.resolve("es/menus/admin/settings_ah.yml"), PAGE.formatted("Tasa de venta", "Se cobra al vendedor.", "Tasa"));
        // A player menu with the same action shape, on a page no hub links to.
        write(root.resolve("en/menus/settings_targets.yml"), """
                items:
                  size:
                    name: 'Size'
                    actions:
                      - 'market:setting size 1'
                """);
        return root;
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private static SettingsScreens.Candidate file(String key, String path, String values) {
        return new SettingsScreens.Candidate(key, path, JsonParser.parseString(values).getAsJsonObject(), null);
    }

    @Test
    void pagesCardsAndTheirTranslationsComeFromTheShippedMenus(@TempDir Path root) throws IOException {
        SettingsScreens.Screens screens = SettingsScreens.read(lang(root));
        JsonObject json = SettingsScreens.json(screens, List.of(
                file("Market/core", "config.yml", "{\"enabled\": true}"),
                file("Market/ah", "auction-house.yml", "{\"settings\": {\"listing-fee-percent\": 5}}")), "Market");

        assertEquals("Todos los ajustes.", json.getAsJsonObject("about").getAsJsonArray("es").get(0).getAsString());
        assertEquals(1, json.getAsJsonArray("pages").size(), "the player menu is not a settings page");
        JsonObject page = json.getAsJsonArray("pages").get(0).getAsJsonObject();
        assertEquals("Market/ah", page.get("subject").getAsString());
        assertEquals("Casa de subastas", page.getAsJsonObject("title").get("es").getAsString());
        assertEquals("Fees and limits.", page.getAsJsonObject("summary").getAsJsonArray("en").get(0).getAsString());
        assertEquals("What listing costs.", page.getAsJsonObject("about").getAsJsonArray("en").get(0).getAsString());
        JsonObject stat = page.getAsJsonArray("stats").get(0).getAsJsonObject();
        assertEquals(1, page.getAsJsonArray("stats").size(), "the module state is not a key");
        assertEquals("settings.listing-fee-percent", stat.get("key").getAsString());
        assertEquals("Tasa", stat.getAsJsonObject("label").get("es").getAsString());

        JsonObject card = page.getAsJsonArray("cards").get(0).getAsJsonObject();
        assertEquals("settings.listing-fee-percent", card.get("key").getAsString());
        assertEquals("PAPER", card.get("material").getAsString());
        assertEquals("gold", card.get("icon").getAsString());
        assertEquals("Tasa de venta", card.getAsJsonObject("title").get("es").getAsString());
        assertEquals(JsonParser.parseString("[\"Charged to the seller.\", \"{highlight}-1 {letters}removes it.\"]"),
                card.getAsJsonObject("lines").get("en"));
        assertEquals("Fee", card.getAsJsonObject("label").get("en").getAsString());
    }

    @Test
    void aPageTwoFilesCouldEditIsLeftOutUnlessItsNameSettlesIt(@TempDir Path root) throws IOException {
        SettingsScreens.Screens screens = SettingsScreens.read(lang(root));
        String values = "{\"settings\": {\"listing-fee-percent\": 5}}";

        assertNull(SettingsScreens.json(screens, List.of(
                file("Market/a", "auctions.yml", values), file("Market/b", "orders.yml", values)), "Market"));

        JsonObject named = SettingsScreens.json(screens, List.of(
                file("Market/a", "ah/settings.yml", values), file("Market/b", "orders.yml", values)), "Market");
        assertEquals("Market/a", named.getAsJsonArray("pages").get(0).getAsJsonObject().get("subject").getAsString());
    }

    @Test
    void aJarWithoutSettingsScreensSendsNone(@TempDir Path root) throws IOException {
        write(root.resolve("en/messages.yml"), "prefix: x\n");
        assertEquals(0, SettingsScreens.read(root).pages().size());
    }
}
