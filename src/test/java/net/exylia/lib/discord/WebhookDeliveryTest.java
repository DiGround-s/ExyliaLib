package net.exylia.lib.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.exylia.lib.FakeServer;
import net.exylia.lib.discord.internal.WebhookRuntime;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Delivery against a scripted Discord: order, rate limits, retries, dead webhooks and bounds. */
class WebhookDeliveryTest {

    private static final WebhookTarget TARGET = WebhookTarget.parse(WebhookTargetTest.URL).target();

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Integer, WebhookRuntime.Reply> discord = attempt -> reply(204);
    private WebhookRuntime runtime;
    private Plugin plugin;
    private PluginWebhooks webhooks;

    @BeforeEach
    void setUp() {
        AtomicInteger counter = new AtomicInteger();
        runtime = WebhookRuntime.installForTests((method, uri, json) -> {
            assertEquals(URI.create(WebhookTargetTest.URL + (method.equals("POST") ? "?with_components=true" : "")),
                    uri, "always the rebuilt URL");
            requests.add(method + " " + json);
            return discord.apply(counter.incrementAndGet());
        }, (millis, task) -> executor.schedule(task, millis, TimeUnit.MILLISECONDS), warnings::add);
        plugin = FakeServer.newPlugin("WebhookTest");
        webhooks = Webhooks.of(plugin);
    }

    @AfterEach
    void tearDown() {
        Webhooks.release(plugin);
        executor.shutdownNow();
    }

    private static WebhookRuntime.Reply reply(int status) {
        return new WebhookRuntime.Reply(status, Map.of(), "");
    }

    private WebhookResult await(java.util.concurrent.CompletionStage<WebhookResult> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void sendsInOrder() throws Exception {
        List<CompletableFuture<WebhookResult>> sent = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sent.add(webhooks.send(TARGET, new WebhookTemplate("T", "message " + i)).toCompletableFuture());
        }
        for (CompletableFuture<WebhookResult> future : sent) {
            assertEquals(WebhookResult.SENT, await(future));
        }
        for (int i = 0; i < 5; i++) {
            assertTrue(requests.get(i).contains("message " + i), requests.get(i));
        }
        JsonObject body = JsonParser.parseString(requests.get(0).substring(5)).getAsJsonObject();
        assertTrue(body.has("allowed_mentions"));
    }

    @Test
    void deadWebhookIsReportedOnceAndNeverAskedAgain() throws Exception {
        discord = attempt -> reply(404);
        ConcurrentLinkedQueue<WebhookTarget> invalidated = new ConcurrentLinkedQueue<>();
        webhooks.onInvalidated(invalidated::add);
        assertEquals(WebhookResult.INVALIDATED, await(webhooks.send(TARGET, new WebhookTemplate("T", "x"))));
        assertEquals(WebhookResult.INVALIDATED, await(webhooks.send(TARGET, new WebhookTemplate("T", "y"))));
        assertEquals(1, requests.size(), "the second send short-circuits");
        assertEquals(List.of(TARGET), List.copyOf(invalidated));
        WebhookCheck check = webhooks.verify(TARGET).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(WebhookCheck.Status.NOT_FOUND, check.status());
        assertEquals(1, requests.size(), "verify short-circuits too");
    }

    @Test
    void honoursRetryAfter() throws Exception {
        discord = attempt -> attempt == 1
                ? new WebhookRuntime.Reply(429, Map.of(), "{\"retry_after\": 0.6, \"global\": false}")
                : reply(204);
        long start = System.currentTimeMillis();
        assertEquals(WebhookResult.SENT, await(webhooks.send(TARGET, new WebhookTemplate("T", "x"))));
        assertTrue(System.currentTimeMillis() - start >= 550, "waited for the rate limit");
        assertEquals(2, requests.size());
    }

    @Test
    void waitsForAnEmptyBucket() throws Exception {
        discord = attempt -> new WebhookRuntime.Reply(204,
                Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset-after", "0.6"), "");
        await(webhooks.send(TARGET, new WebhookTemplate("T", "x")));
        long start = System.currentTimeMillis();
        await(webhooks.send(TARGET, new WebhookTemplate("T", "y")));
        assertTrue(System.currentTimeMillis() - start >= 450, "the second waited for the reset");
    }

    @Test
    void givesUpAfterThreeAttempts() throws Exception {
        discord = attempt -> reply(502);
        assertEquals(WebhookResult.FAILED, await(webhooks.send(TARGET, new WebhookTemplate("T", "x"))));
        assertEquals(3, requests.size());
        discord = attempt -> reply(400);
        assertEquals(WebhookResult.FAILED, await(webhooks.send(TARGET, new WebhookTemplate("T", "y"))));
        assertEquals(4, requests.size(), "a 400 is not retried");
    }

    @Test
    void coalescesOneTemplateWithinItsWindow() throws Exception {
        WebhookTemplate template = new WebhookTemplate(true, "", "", "", "{primary}", "Log", "",
                "%player% joined", new WebhookTemplate.Author(), "", "", List.of(), "", false,
                Duration.ofMillis(300));
        var a = webhooks.send(TARGET, template, Map.of("player", "A"));
        var b = webhooks.send(TARGET, template, Map.of("player", "B"));
        var c = webhooks.send(TARGET, template, Map.of("player", "C"));
        assertEquals(WebhookResult.SENT, await(a));
        assertEquals(WebhookResult.SENT, await(b));
        assertEquals(WebhookResult.SENT, await(c));
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).contains("A joined\\nB joined\\nC joined"), requests.get(0));
    }

    @Test
    void boundsTheQueueByDroppingTheOldest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        discord = attempt -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return reply(204);
        };
        var inFlight = webhooks.send(TARGET, new WebhookTemplate("T", "first"));
        while (requests.isEmpty()) {
            Thread.onSpinWait();
        }
        List<CompletableFuture<WebhookResult>> queued = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            queued.add(webhooks.send(TARGET, new WebhookTemplate("T", "q" + i)).toCompletableFuture());
        }
        assertEquals(WebhookResult.DROPPED, await(queued.get(0)), "the oldest waiting one gives way");
        release.countDown();
        assertEquals(WebhookResult.SENT, await(inFlight));
        assertEquals(WebhookResult.SENT, await(queued.get(50)));
        assertEquals(1, warnings.size(), "drops are reported, once");
    }

    @Test
    void releasingThePluginDropsWhatItHadWaiting() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        discord = attempt -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return reply(204);
        };
        var inFlight = webhooks.send(TARGET, new WebhookTemplate("T", "first"));
        while (requests.isEmpty()) {
            Thread.onSpinWait();
        }
        var waiting = webhooks.send(TARGET, new WebhookTemplate("T", "second"));
        Webhooks.release(plugin);
        assertEquals(WebhookResult.DROPPED, await(waiting));
        release.countDown();
        assertEquals(WebhookResult.SENT, await(inFlight), "what already left still lands");
        assertEquals(0, runtime.queued());
    }

    @Test
    void verifyReadsTheWebhook() throws Exception {
        discord = attempt -> new WebhookRuntime.Reply(200, Map.of(),
                "{\"id\":\"1\",\"name\":\"Clan log\",\"channel_id\":\"42\",\"guild_id\":\"7\",\"token\":\"secret\"}");
        WebhookCheck check = webhooks.verify(TARGET).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(check.connected());
        assertEquals("Clan log", check.name());
        assertEquals("42", check.channelId());
        assertEquals("7", check.guildId());
        assertTrue(requests.get(0).startsWith("GET"));
    }

    @Test
    void skipsDisabledAndEmptyTemplates() throws Exception {
        WebhookTemplate disabled = new WebhookTemplate(false, "hi", "", "", "", "", "", "",
                new WebhookTemplate.Author(), "", "", List.of(), "", true, Duration.ZERO);
        assertEquals(WebhookResult.SKIPPED, await(webhooks.send(TARGET, disabled)));
        assertEquals(WebhookResult.SKIPPED, await(webhooks.send(TARGET, new WebhookTemplate())));
        assertEquals(WebhookResult.SKIPPED, await(webhooks.send(TARGET, new WebhookTemplate())));
        assertEquals(1, warnings.size(), "an empty template is reported once");
        assertTrue(requests.isEmpty());
    }

    @Test
    void playerOwnedNeverPingsEveryone() throws Exception {
        WebhookTemplate template = new WebhookTemplate("T", "x").withMessage("@everyone");
        await(webhooks.playerOwned().send(TARGET, template));
        await(webhooks.send(TARGET, template));
        assertTrue(requests.get(0).contains("\"parse\":[]"), requests.get(0));
        assertTrue(requests.get(1).contains("\"parse\":[\"everyone\"]"), requests.get(1));
    }
}
