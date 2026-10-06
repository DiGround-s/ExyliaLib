package net.exylia.lib.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The URL is the trust boundary: only Discord's own shape gets through. */
class WebhookTargetTest {

    static final String ID = "123456789012345678";
    static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-AbCdEfGhIjKlMnOpQrStUvWxYz01";
    static final String URL = "https://discord.com/api/webhooks/" + ID + "/" + TOKEN;

    @Test
    void acceptsEveryHostDiscordHandsOut() {
        for (String host : new String[]{"discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com",
                "Discord.com"}) {
            WebhookTarget.Parsed parsed = WebhookTarget.parse("https://" + host + "/api/webhooks/" + ID + "/" + TOKEN);
            assertTrue(parsed.ok(), host);
            assertEquals(ID, parsed.target().id());
            assertEquals(TOKEN, parsed.target().token());
        }
        assertTrue(WebhookTarget.parse("  " + URL + "/  ").ok(), "trailing slash and whitespace");
    }

    @Test
    void rebuildsTheCanonicalUrl() {
        WebhookTarget target = WebhookTarget.parse("https://canary.discord.com/api/webhooks/" + ID + "/" + TOKEN + "/")
                .target();
        assertEquals(URL, target.secret());
        assertEquals(target, WebhookTarget.parse(target.secret()).target(), "stored and restored");
    }

    @Test
    void rejectsEverythingElseWithTheReason() {
        String path = "/api/webhooks/" + ID + "/" + TOKEN;
        assertRejected("", WebhookTarget.Rejection.BLANK);
        assertRejected(null, WebhookTarget.Rejection.BLANK);
        assertRejected("not a url", WebhookTarget.Rejection.MALFORMED);
        assertRejected("http://discord.com" + path, WebhookTarget.Rejection.NOT_HTTPS);
        assertRejected("ftp://discord.com" + path, WebhookTarget.Rejection.NOT_HTTPS);
        assertRejected("https://evil.com" + path, WebhookTarget.Rejection.HOST);
        assertRejected("https://discord.com.evil.com" + path, WebhookTarget.Rejection.HOST);
        assertRejected("https://evildiscord.com" + path, WebhookTarget.Rejection.HOST);
        assertRejected("https://162.159.128.233" + path, WebhookTarget.Rejection.HOST);
        assertRejected("https://discord.com." + path, WebhookTarget.Rejection.HOST);
        assertRejected("https://x@discord.com" + path, WebhookTarget.Rejection.EXTRA);
        assertRejected("https://discord.com@evil.com" + path, WebhookTarget.Rejection.EXTRA);
        assertRejected("https://discord.com:443" + path, WebhookTarget.Rejection.EXTRA);
        assertRejected("https://discord.com" + path + "?wait=true", WebhookTarget.Rejection.EXTRA);
        assertRejected("https://discord.com" + path + "#x", WebhookTarget.Rejection.EXTRA);
        assertRejected("https://discord.com/api/webhooks/../oauth2/" + ID + "/" + TOKEN, WebhookTarget.Rejection.PATH);
        assertRejected("https://discord.com/api/webhooks/" + ID + "/" + TOKEN + "/github", WebhookTarget.Rejection.PATH);
        assertRejected("https://discord.com/api/v10/webhooks/" + ID + "/" + TOKEN, WebhookTarget.Rejection.PATH);
        assertRejected("https://discord.com/api/webhooks/" + ID + "/" + TOKEN.substring(0, 20),
                WebhookTarget.Rejection.INCOMPLETE);
        assertRejected("https://discord.com/api/webhooks/1234/" + TOKEN, WebhookTarget.Rejection.INCOMPLETE);
        assertRejected("https://discord.com/api/webhooks/" + ID + "/" + TOKEN.replace('A', '%'),
                WebhookTarget.Rejection.INCOMPLETE);
    }

    @Test
    void neverPrintsTheToken() {
        WebhookTarget target = WebhookTarget.parse(URL).target();
        assertFalse(target.toString().contains(TOKEN));
        assertFalse(target.toString().contains(ID));
        assertEquals("https://discord.com/api/webhooks/1234…/••••", target.toString());
        assertEquals(target.masked(), String.valueOf(target));
    }

    private static void assertRejected(String raw, WebhookTarget.Rejection expected) {
        WebhookTarget.Parsed parsed = WebhookTarget.parse(raw);
        assertFalse(parsed.ok(), raw);
        assertEquals(expected, parsed.rejection(), raw);
    }
}
