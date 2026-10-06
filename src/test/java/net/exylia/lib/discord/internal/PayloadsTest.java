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
        String content = payload.get("content").getAsString();
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
        JsonObject embed = embed(build(template,
                Map.of("server", Webhooks.trusted("**Survival**"), "player", "**Steve**"), false));
        assertEquals("**Survival**", embed.get("title").getAsString());
        assertEquals("\\*\\*Steve\\*\\*", embed.get("description").getAsString());
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
        assertEquals(Payloads.CONTENT, payload.get("content").getAsString().length());
        assertTrue(payload.get("content").getAsString().endsWith("…"));
        assertEquals(Payloads.USERNAME, payload.get("username").getAsString().length());
        JsonObject embed = embed(payload);
        assertEquals(Payloads.TITLE, embed.get("title").getAsString().length());
        assertEquals(Payloads.AUTHOR, embed.getAsJsonObject("author").get("name").getAsString().length());
        JsonArray fields = embed.getAsJsonArray("fields");
        assertTrue(fields.size() <= Payloads.FIELDS);
        int total = embed.get("title").getAsString().length()
                + (embed.has("description") ? embed.get("description").getAsString().length() : 0)
                + embed.getAsJsonObject("footer").get("text").getAsString().length()
                + embed.getAsJsonObject("author").get("name").getAsString().length();
        for (var field : fields) {
            JsonObject object = field.getAsJsonObject();
            assertTrue(object.get("name").getAsString().length() <= Payloads.FIELD_NAME);
            assertTrue(object.get("value").getAsString().length() <= Payloads.FIELD_VALUE);
            total += object.get("name").getAsString().length() + object.get("value").getAsString().length();
        }
        assertTrue(total <= Payloads.EMBED_TOTAL, "total " + total);
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
        assertEquals("hello", content.get("content").getAsString());
        assertFalse(content.has("embeds"), "colour and timestamp alone are not an embed");
        assertNull(build(new WebhookTemplate("", ""), Map.of(), false));
    }

    @Test
    void dropsUrlsThatAreNotUrls() {
        WebhookTemplate template = new WebhookTemplate(true, "", "", "javascript:alert(1)", "", "T",
                "%link%", "", new WebhookTemplate.Author("A", "%icon%", ""), "https://ok.example/x.png", "", List.of(),
                "", false, Duration.ZERO);
        JsonObject payload = build(template, Map.of("link", "not a url", "icon", "https://mc-heads.net/avatar/x/64"), false);
        assertFalse(payload.has("avatar_url"));
        JsonObject embed = embed(payload);
        assertFalse(embed.has("url"));
        assertEquals("https://mc-heads.net/avatar/x/64", embed.getAsJsonObject("author").get("icon_url").getAsString());
        assertEquals("https://ok.example/x.png", embed.getAsJsonObject("thumbnail").get("url").getAsString());
    }

    @Test
    void splitsFieldsOnTheTemplateNotOnTheValue() {
        WebhookTemplate template = new WebhookTemplate("T", "").withFields(List.of("Player|%player%|inline", "Only"));
        JsonArray fields = embed(build(template, Map.of("player", "a|b"), false)).getAsJsonArray("fields");
        assertEquals("a\\|b", fields.get(0).getAsJsonObject().get("value").getAsString());
        assertTrue(fields.get(0).getAsJsonObject().get("inline").getAsBoolean());
        assertEquals("​", fields.get(1).getAsJsonObject().get("value").getAsString());
    }

    @Test
    void mergesDescriptionsUpToTheLimit() {
        JsonObject first = build(new WebhookTemplate("Log", "one"), Map.of(), false);
        assertTrue(Payloads.merge(first, build(new WebhookTemplate("Log", "two"), Map.of(), false)));
        assertEquals("one\ntwo", embed(first).get("description").getAsString());
        JsonObject huge = build(new WebhookTemplate("Log", "y".repeat(4095)), Map.of(), false);
        assertFalse(Payloads.merge(first, huge));
    }

    private static JsonObject embed(JsonObject payload) {
        return payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
    }
}
