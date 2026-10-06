package net.exylia.lib.discord.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.exylia.lib.discord.WebhookTemplate;
import net.exylia.lib.discord.Webhooks;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a message says: escaping, pings, limits and colours, with no network. */
class PayloadsTest {

    private static final String ROLE = "123456789012345678";
    private final List<String> warnings = new ArrayList<>();

    private JsonObject build(WebhookTemplate template, Map<String, ?> values, boolean everyone) {
        return Payloads.build(template, values, everyone, warnings::add);
    }

    @Test
    void pingsOnlyWhatTheTemplateWrote() {
        WebhookTemplate template = new WebhookTemplate("T", "").withMessage(
                "<@&" + ROLE + "> <@!223456789012345678> @everyone %player% started");
        JsonObject payload = build(template, Map.of("player", "<@&999999999999999999> @here"), true);
        JsonObject allowed = payload.getAsJsonObject("allowed_mentions");
        assertEquals("[\"everyone\"]", allowed.get("parse").toString());
        assertEquals("[\"" + ROLE + "\"]", allowed.get("roles").toString());
        assertEquals("[\"223456789012345678\"]", allowed.get("users").toString());
        String content = message(payload);
        assertTrue(content.startsWith("<@&" + ROLE + "> <@!223456789012345678> @everyone "), content);
        assertFalse(content.contains("<@&999999999999999999>"), "an injected role mention is broken");
        assertFalse(content.contains("@here"), "an injected @here is broken");
    }

    @Test
    void parseStaysEmptyWhenEveryoneIsNotAllowed() {
        WebhookTemplate template = new WebhookTemplate("T", "").withMessage("@everyone wake up");
        JsonObject allowed = build(template, Map.of(), false).getAsJsonObject("allowed_mentions");
        assertEquals("[]", allowed.get("parse").toString());
        assertEquals("[]", allowed.get("roles").toString());
        assertEquals("[]", allowed.get("users").toString());
        // And with no message at all, mentions are still pinned down.
        JsonObject none = build(new WebhookTemplate("T", "x"), Map.of(), true).getAsJsonObject("allowed_mentions");
        assertEquals("[]", none.get("parse").toString());
    }

    @Test
    void capsMentionedIdsAndSaysSo() {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            message.append("<@&").append(100000000000000000L + i).append('>');
        }
        JsonObject allowed = build(new WebhookTemplate("T", "").withMessage(message.toString()), Map.of(), false)
                .getAsJsonObject("allowed_mentions");
        assertEquals(100, allowed.getAsJsonArray("roles").size());
        assertEquals(1, warnings.size());
    }

    @Test
    void neutralisesInjectedTextInEveryPart() {
        String evil = "@everyone **bold** [link](https://x.y) `code` <@&" + ROLE + "> &cred <red>tag";
        String safe = Payloads.neutralise(evil);
        assertFalse(safe.contains("@everyone"));
        assertFalse(safe.contains("**"));
        assertFalse(safe.contains("[link]"));
        assertFalse(safe.contains("<@&"));
        assertFalse(safe.contains("&c"));
        assertFalse(safe.contains("<red>"));
        assertTrue(safe.contains("\\*\\*bold\\*\\*"), safe);
        assertEquals("a b", Payloads.neutralise("a\nb"), "no fake lines");
        assertEquals("Steve", Payloads.neutralise("Steve"));
    }

    @Test
    void trustedValuesKeepTheirMarkdown() {
        WebhookTemplate template = new WebhookTemplate("%server%", "%player%");
        JsonObject payload = build(template,
                Map.of("server", Webhooks.trusted("**Survival**"), "player", "**Steve**"), false);
        assertEquals("## **Survival**", texts(payload).get(0));
        assertEquals("\\*\\*Steve\\*\\*", description(payload));
    }

    @Test
    void convertsMinecraftFormattingInTheTemplate() {
        assertEquals("**CAPTURE** started", Payloads.convert("{primary}&lCAPTURE &7started"));
        assertEquals("**BOLD**", Payloads.convert("&lBOLD"));
        assertEquals("plain", Payloads.convert("<gradient:#fff:#000>plain</gradient>"));
        assertEquals("<@&" + ROLE + "> hi <t:1700000000:R>", Payloads.convert("<@&" + ROLE + "> &ahi <t:1700000000:R>"));
        assertEquals("keep **this** and %zone%", Payloads.convert("keep **this** and %zone%"));
    }

    @Test
    void cutsToDiscordLimitsInsteadOfFailing() {
        String longText = "x".repeat(5000);
        WebhookTemplate template = new WebhookTemplate(true, longText, longText, "", "", longText, "",
                longText, new WebhookTemplate.Author(longText, "", ""), "", "",
                java.util.Collections.nCopies(30, longText + "|" + longText), longText, false, Duration.ZERO);
        JsonObject payload = build(template, Map.of(), false);
        assertEquals(Payloads.CONTENT, message(payload).length());
        assertTrue(message(payload).endsWith("…"));
        assertEquals(Payloads.USERNAME, payload.get("username").getAsString().length());
        assertTrue(Payloads.textLength(payload.getAsJsonArray("components")) <= Payloads.TEXT_TOTAL,
                "total " + Payloads.textLength(payload.getAsJsonArray("components")));
    }

    @Test
    void laysTheCardOutAsComponents() {
        WebhookTemplate template = new WebhookTemplate(true, "<@&" + ROLE + "> news", "", "", "#123abc", "Title",
                "https://example.com", "Body", new WebhookTemplate.Author("%player%", "https://example.com/head.png", ""),
                "", "https://example.com/big.png", List.of("A|1|inline", "B|2|inline", "C|3"),
                List.of("Docs|https://docs.example.com", "Review|https://example.com/r|⭐", "Bad|not a url"),
                "Footer", true, Duration.ZERO);
        JsonObject payload = build(template, Map.of("player", "Steve"), false);
        assertEquals(Payloads.FLAG_COMPONENTS, payload.get("flags").getAsInt());
        assertFalse(payload.has("content"));
        assertFalse(payload.has("embeds"));
        JsonArray top = payload.getAsJsonArray("components");
        assertEquals(Payloads.TEXT, top.get(0).getAsJsonObject().get("type").getAsInt(), "the ping sits outside");
        JsonObject card = top.get(1).getAsJsonObject();
        assertEquals(0x123abc, card.get("accent_color").getAsInt());
        JsonArray parts = card.getAsJsonArray("components");
        JsonObject section = parts.get(0).getAsJsonObject();
        assertEquals(Payloads.SECTION, section.get("type").getAsInt(), "the author icon becomes the thumbnail");
        assertEquals("https://example.com/head.png",
                section.getAsJsonObject("accessory").getAsJsonObject("media").get("url").getAsString());
        assertEquals("-# Steve\n## [Title](https://example.com)", texts(payload).get(1));
        assertEquals("**A:** 1\n**B:** 2\n\n**C**\n3", texts(payload).get(3));
        assertEquals(Payloads.GALLERY, parts.get(2).getAsJsonObject().get("type").getAsInt());
        JsonArray buttons = parts.get(3).getAsJsonObject().getAsJsonArray("components");
        assertEquals(2, buttons.size(), "a button without a usable URL is left out");
        assertEquals("https://docs.example.com", buttons.get(0).getAsJsonObject().get("url").getAsString());
        assertEquals("⭐", buttons.get(1).getAsJsonObject().getAsJsonObject("emoji").get("name").getAsString());
        assertTrue(texts(payload).get(4).matches("-# Footer · <t:\\d+:f>"), texts(payload).get(4));
    }

    @Test
    void putsButtonsFiveToARow() {
        List<String> buttons = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            buttons.add("B" + i + "|https://example.com/" + i + "|<:exylia:123456789012345678>");
        }
        JsonObject payload = build(new WebhookTemplate("T", "").withButtons(buttons), Map.of(), false);
        JsonArray parts = payload.getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("components");
        int rows = 0;
        for (var part : parts) {
            if (part.getAsJsonObject().get("type").getAsInt() == Payloads.ACTION_ROW) {
                assertTrue(part.getAsJsonObject().getAsJsonArray("components").size() <= 5);
                rows++;
            }
        }
        assertEquals(Payloads.BUTTONS / 5, rows);
        JsonObject emoji = parts.get(1).getAsJsonObject().getAsJsonArray("components").get(0).getAsJsonObject()
                .getAsJsonObject("emoji");
        assertEquals("123456789012345678", emoji.get("id").getAsString());
    }

    @Test
    void resolvesColoursFromThePaletteOrHex() {
        // Read from the live palette, whatever another test left in it.
        assertEquals(net.exylia.lib.text.Colors.get("primary").value(), Payloads.color("{primary}"));
        assertEquals(net.exylia.lib.text.Colors.get("error").value(), Payloads.color("{error}"));
        assertEquals(0x123abc, Payloads.color("#123abc"));
        assertEquals(0x123abc, Payloads.color("<#123ABC>"));
        assertNull(Payloads.color("{no_such_token}"));
        assertNull(Payloads.color("red"));
        assertNull(Payloads.color(""));
    }

    @Test
    void sendsContentAloneOrNothing() {
        JsonObject content = build(new WebhookTemplate("", "").withMessage("hello"), Map.of(), false);
        assertEquals("hello", message(content));
        assertEquals(1, content.getAsJsonArray("components").size(), "colour and timestamp alone are not a card");
        assertNull(build(new WebhookTemplate("", ""), Map.of(), false));
    }

    @Test
    void dropsUrlsThatAreNotUrls() {
        WebhookTemplate template = new WebhookTemplate(true, "", "", "javascript:alert(1)", "", "T",
                "%link%", "", new WebhookTemplate.Author("A", "%icon%", ""), "https://ok.example/x.png", "", List.of(),
                "", false, Duration.ZERO);
        JsonObject payload = build(template, Map.of("link", "not a url", "icon", "https://mc-heads.net/avatar/x/64"), false);
        assertFalse(payload.has("avatar_url"));
        assertEquals("-# A\n## T", texts(payload).get(0), "a title link that is not a URL is dropped");
        JsonObject section = payload.getAsJsonArray("components").get(0).getAsJsonObject()
                .getAsJsonArray("components").get(0).getAsJsonObject();
        assertEquals("https://ok.example/x.png",
                section.getAsJsonObject("accessory").getAsJsonObject("media").get("url").getAsString(),
                "the thumbnail wins over the author icon");
    }

    @Test
    void splitsFieldsOnTheTemplateNotOnTheValue() {
        WebhookTemplate template = new WebhookTemplate("T", "").withFields(List.of("Player|%player%|inline", "Only"));
        List<String> texts = texts(build(template, Map.of("player", "a|b"), false));
        assertEquals("**Player:** a\\|b\n\n**Only**", texts.get(1));
    }

    @Test
    void mergesDescriptionsUpToTheLimit() {
        JsonObject first = build(new WebhookTemplate("Log", "one"), Map.of(), false);
        assertTrue(Payloads.merge(first, build(new WebhookTemplate("Log", "two"), Map.of(), false)));
        assertEquals("one\ntwo", description(first));
        JsonObject huge = build(new WebhookTemplate("Log", "y".repeat(3990)), Map.of(), false);
        assertFalse(Payloads.merge(first, huge));
        JsonObject plain = build(new WebhookTemplate("", "").withMessage("a"), Map.of(), false);
        assertTrue(Payloads.merge(plain, build(new WebhookTemplate("", "").withMessage("b"), Map.of(), false)));
        assertEquals("a\nb", message(plain));
        assertFalse(Payloads.merge(plain, first), "a card never merges into a plain message");
    }

    private static String message(JsonObject payload) {
        return Payloads.find(payload.getAsJsonArray("components"), Payloads.MESSAGE_ID).get("content").getAsString();
    }

    private static String description(JsonObject payload) {
        return Payloads.find(payload.getAsJsonArray("components"), Payloads.DESCRIPTION_ID).get("content").getAsString();
    }

    /** Every text display's content, in order. */
    private static List<String> texts(JsonObject payload) {
        List<String> texts = new ArrayList<>();
        collect(payload.getAsJsonArray("components"), texts);
        return texts;
    }

    private static void collect(JsonArray components, List<String> texts) {
        for (var element : components) {
            JsonObject component = element.getAsJsonObject();
            if (component.get("type").getAsInt() == Payloads.TEXT) {
                texts.add(component.get("content").getAsString());
            }
            if (component.has("components")) {
                collect(component.getAsJsonArray("components"), texts);
            }
        }
    }
}
