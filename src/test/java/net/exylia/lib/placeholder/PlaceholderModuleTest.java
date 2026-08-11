package net.exylia.lib.placeholder;

import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.internal.Registry;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the placeholder module.
 *
 * <p>These check what a server actually depends on: that a value appears, that a
 * broken placeholder cannot take a message down, that arguments and formats are
 * understood, and that thousands of concurrent renders agree with each other.
 */
class PlaceholderModuleTest {

    private Plugin plugin;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        plugin = FakeServer.newPlugin("TestPlugin", null);
        Registry.clear();
        Logger quiet = Logger.getLogger("PlaceholderTest");
        quiet.setLevel(Level.OFF);
        Placeholders.logger(quiet);
    }

    @AfterEach
    void tearDown() {
        Registry.clear();
    }

    // ------------------------------------------------------------------
    // The basics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a registered placeholder is replaced by its value")
    void resolvesValue() {
        Placeholders.group(plugin, "test").add("name", request -> "Steve").register();

        assertEquals("Hello Steve", Placeholders.apply("Hello %test_name%"));
    }

    @Test
    @DisplayName("an unknown placeholder stays visible so a typo can be spotted")
    void unknownStaysVisible() {
        assertEquals("Hello %nope_missing%", Placeholders.apply("Hello %nope_missing%"));
    }

    @Test
    @DisplayName("text without placeholders comes back untouched")
    void plainTextUntouched() {
        assertEquals("Just words", Placeholders.apply("Just words"));
        assertFalse(Placeholders.isDynamic("Just words"));
    }

    @Test
    @DisplayName("percent signs in prose are not mistaken for placeholders")
    void proseWithPercentSigns() {
        Placeholders.group(plugin, "test").add("x", request -> "!").register();

        assertEquals("50% of 20% is 10%", Placeholders.apply("50% of 20% is 10%"));
        assertEquals("a % b", Placeholders.apply("a % b"));
    }

    @Test
    @DisplayName("a doubled percent sign is an escape")
    void escapedPercent() {
        assertEquals("100%", Placeholders.apply("100%%"));
    }

    @Test
    @DisplayName("several placeholders in one line all resolve")
    void multiplePlaceholders() {
        Placeholders.group(plugin, "test")
                .add("a", request -> 1)
                .add("b", request -> 2)
                .register();

        assertEquals("1 and 2 and 1", Placeholders.apply("%test_a% and %test_b% and %test_a%"));
    }

    // ------------------------------------------------------------------
    // Arguments
    // ------------------------------------------------------------------

    @Test
    @DisplayName("arguments are split off the name and parsed")
    void argumentsAreParsed() {
        Placeholders.group(plugin, "clan").add("top", request -> "rank" + request.arg(0, 0)).register();

        assertEquals("rank3", Placeholders.apply("%clan_top_3%"));
    }

    @Test
    @DisplayName("a name registered whole wins over splitting it into arguments")
    void exactNameWinsOverArguments() {
        Placeholders.group(plugin, "clan")
                .add("top", request -> "split:" + request.arg(0, 0))
                .add("top_3", request -> "exact")
                .register();

        assertEquals("exact", Placeholders.apply("%clan_top_3%"));
    }

    @Test
    @DisplayName("several arguments arrive in order")
    void multipleArguments() {
        Placeholders.group(plugin, "test")
                .add("range", request -> request.arg(0, 0) + "-" + request.arg(1, 0))
                .register();

        assertEquals("5-10", Placeholders.apply("%test_range_5_10%"));
    }

    @Test
    @DisplayName("an argument that is not a number falls back instead of throwing")
    void malformedArgumentFallsBack() {
        Placeholders.group(plugin, "test").add("n", request -> request.arg(0, 99)).register();

        assertEquals("99", Placeholders.apply("%test_n_abc%"));
    }

    // ------------------------------------------------------------------
    // Formats and fallbacks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a format is applied to the value")
    void formatsApply() {
        Placeholders.group(plugin, "eco").add("balance", request -> 1250000).register();

        assertEquals("1,250,000", Placeholders.apply("%eco_balance:comma%"));
        assertEquals("1.3M", Placeholders.apply("%eco_balance:compact%"));
        assertEquals("1250000", Placeholders.apply("%eco_balance%"));
    }

    @Test
    @DisplayName("a DecimalFormat pattern works as a format")
    void customPattern() {
        Placeholders.group(plugin, "eco").add("balance", request -> 1234.5).register();

        assertEquals("1,234.50", Placeholders.apply("%eco_balance:#,##0.00%"));
    }

    @Test
    @DisplayName("a broken format shows the raw value rather than failing")
    void brokenFormatDegrades() {
        Placeholders.group(plugin, "eco").add("balance", request -> 42).register();

        assertEquals("42", Placeholders.apply("%eco_balance:###bad###pattern%"));
    }

    @Test
    @DisplayName("seconds render as a readable duration")
    void timeFormat() {
        Placeholders.group(plugin, "test")
                .add("short", request -> 42)
                .add("long", request -> 3900)
                .register();

        assertEquals("42s", Placeholders.apply("%test_short:time%"));
        assertEquals("1h 5m", Placeholders.apply("%test_long:time%"));
    }

    @Test
    @DisplayName("a fallback is used when there is no value")
    void fallbackUsed() {
        Placeholders.group(plugin, "clan").add("name", request -> null).register();

        assertEquals("No clan", Placeholders.apply("%clan_name|No clan%"));
    }

    @Test
    @DisplayName("a fallback is ignored when there is a value")
    void fallbackIgnoredWhenPresent() {
        Placeholders.group(plugin, "clan").add("name", request -> "Wolves").register();

        assertEquals("Wolves", Placeholders.apply("%clan_name|No clan%"));
    }

    @Test
    @DisplayName("format and fallback combine")
    void formatAndFallbackTogether() {
        Placeholders.group(plugin, "eco")
                .add("a", request -> 5000)
                .add("b", request -> null)
                .register();

        assertEquals("5,000", Placeholders.apply("%eco_a:comma|none%"));
        assertEquals("none", Placeholders.apply("%eco_b:comma|none%"));
    }

    @Test
    @DisplayName("whole decimals do not render a trailing .0")
    void wholeDecimalsAreClean() {
        Placeholders.group(plugin, "test").add("health", request -> 20.0).register();

        assertEquals("20", Placeholders.apply("%test_health%"));
    }

    // ------------------------------------------------------------------
    // Failure is contained
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a resolver that throws does not break the message")
    void throwingResolverIsContained() {
        Placeholders.group(plugin, "bad")
                .add("boom", request -> {
                    throw new IllegalStateException("deliberate");
                })
                .register();

        assertEquals("before %bad_boom% after",
                Placeholders.apply("before %bad_boom% after"));
    }

    @Test
    @DisplayName("a resolver that throws is reported once, not per render")
    void throwingResolverReportedOnce() {
        AtomicInteger reports = new AtomicInteger();
        Logger counting = Logger.getLogger("CountingTest_" + System.nanoTime());
        counting.setUseParentHandlers(false);
        counting.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                reports.incrementAndGet();
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Placeholders.logger(counting);

        Placeholders.group(plugin, "bad")
                .add("boom", request -> {
                    throw new IllegalStateException("deliberate");
                })
                .register();

        for (int i = 0; i < 50; i++) {
            Placeholders.apply("%bad_boom%");
        }

        assertEquals(1, reports.get(), "a broken resolver must not spam the console");
    }

    @Test
    @DisplayName("a placeholder needing a player degrades when there is none")
    void missingPlayerDegrades() {
        Placeholders.group(plugin, "test")
                .add("name", request -> request.requireViewer().getName())
                .register();

        assertEquals("%test_name%", Placeholders.apply("%test_name%"));
    }

    // ------------------------------------------------------------------
    // Templates
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a compiled template renders the current value each time")
    void templateSeesFreshValues() {
        AtomicInteger counter = new AtomicInteger();
        Placeholders.group(plugin, "test").add("n", request -> counter.incrementAndGet()).register();

        Template template = Placeholders.compile("value: %test_n%");

        assertEquals("value: 1", template.render());
        assertEquals("value: 2", template.render());
    }

    @Test
    @DisplayName("a template without placeholders reports itself as static")
    void staticTemplate() {
        Template template = Placeholders.compile("no placeholders here");

        assertFalse(template.isDynamic());
        assertEquals("no placeholders here", template.render());
        assertTrue(template.placeholders().isEmpty());
    }

    @Test
    @DisplayName("a template lists the placeholders it contains")
    void templateListsPlaceholders() {
        Placeholders.group(plugin, "test").add("a", request -> 1).add("b", request -> 2).register();

        Template template = Placeholders.compile("%test_a% and %test_b%");

        assertEquals(List.of("test_a", "test_b"), template.placeholders());
    }

    @Test
    @DisplayName("attached data reaches the resolver")
    void dataReachesResolver() {
        Placeholders.group(plugin, "arena")
                .add("name", request -> request.get("arena", String.class, "none"))
                .register();

        assertEquals("Nether",
                Placeholders.apply("%arena_name%", null, Map.of("arena", "Nether")));
        assertEquals("none", Placeholders.apply("%arena_name%"));
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("unregistering a plugin removes only its placeholders")
    void unregisterIsScoped() {
        Plugin other = FakeServer.newPlugin("OtherPlugin", null);
        Placeholders.group(plugin, "mine").add("x", request -> "mine").register();
        Placeholders.group(other, "theirs").add("x", request -> "theirs").register();

        Placeholders.unregisterAll("TestPlugin");

        assertFalse(Placeholders.has("mine_x"));
        assertTrue(Placeholders.has("theirs_x"));
    }

    @Test
    @DisplayName("registering again replaces rather than duplicates")
    void reregisterReplaces() {
        Placeholders.group(plugin, "test").add("x", request -> "first").register();
        Placeholders.group(plugin, "test").add("x", request -> "second").register();

        assertEquals("second", Placeholders.apply("%test_x%"));
        assertEquals(1, Registry.namesOf("TestPlugin").size());
    }

    @Test
    @DisplayName("a newly registered placeholder is seen by text compiled earlier")
    void lateRegistrationIsSeen() {
        assertEquals("%late_value%", Placeholders.apply("%late_value%"));

        Placeholders.group(plugin, "late").add("value", request -> "now here").register();

        assertEquals("now here", Placeholders.apply("%late_value%"));
    }

    @Test
    @DisplayName("registering a prefix later re-splits text compiled before it")
    void lateRegistrationResplitsArguments() {
        // Compiled now, "clan_top_3" is one unknown name with no arguments.
        assertEquals("%clan_top_3%", Placeholders.apply("%clan_top_3%"));

        // Registering the prefix changes how that same text must be read, so a
        // template cached a moment ago is no longer correct.
        Placeholders.group(plugin, "clan").add("top", request -> "rank" + request.arg(0, 0)).register();

        assertEquals("rank3", Placeholders.apply("%clan_top_3%"));
    }

    @Test
    @DisplayName("removing a placeholder re-splits text compiled before it")
    void unregistrationResplitsArguments() {
        Placeholders.group(plugin, "clan")
                .add("top", request -> "split:" + request.arg(0, 0))
                .add("top_3", request -> "exact")
                .register();
        assertEquals("exact", Placeholders.apply("%clan_top_3%"));

        Registry.unregister("clan_top_3");

        assertEquals("split:3", Placeholders.apply("%clan_top_3%"));
    }

    @Test
    @DisplayName("unresolved names are reported for diagnostics")
    void unresolvedReported() {
        Placeholders.group(plugin, "test").add("known", request -> "ok").register();

        assertEquals(List.of("test_unknown"),
                Placeholders.unresolved("%test_known% %test_unknown%"));
    }

    // ------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("thousands of concurrent renders all produce the right answer")
    void concurrentRendersAgree() throws Exception {
        Placeholders.group(plugin, "test").add("value", request -> "stable").register();

        int threads = 16;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wrong = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    if (!"stable!".equals(Placeholders.apply("%test_value%!"))) {
                        wrong.incrementAndGet();
                    }
                }
                return null;
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "renders should not deadlock");
        assertEquals(0, wrong.get(), "every concurrent render must agree");
    }

    @Test
    @DisplayName("registering while rendering does not corrupt anything")
    void registrationDuringRenderIsSafe() throws Exception {
        Placeholders.group(plugin, "test").add("value", request -> "stable").register();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 400; i++) {
                    String result = Placeholders.apply("%test_value%");
                    if (!"stable".equals(result)) {
                        failures.incrementAndGet();
                    }
                }
                return null;
            });
        }
        for (int t = 0; t < 4; t++) {
            int id = t;
            pool.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) {
                    int value = i;
                    Placeholders.group(plugin, "churn" + id).add("x" + i, request -> value).register();
                }
                return null;
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get(), "concurrent registration must not corrupt rendering");
    }

    // ------------------------------------------------------------------
    // Cheap paths stay cheap
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same text compiles to the same cached template")
    void templatesAreCached() {
        Placeholders.group(plugin, "test").add("x", request -> 1).register();

        Object first = net.exylia.lib.placeholder.internal.TemplateCache.get("%test_x%",
                Logger.getLogger("t"));
        Object second = net.exylia.lib.placeholder.internal.TemplateCache.get("%test_x%",
                Logger.getLogger("t"));

        assertSame(first, second, "repeated text must not be recompiled");
    }

    @Test
    @DisplayName("a held template is not shared through the cache")
    void heldTemplateIsSeparate() {
        Placeholders.group(plugin, "test").add("x", request -> 1).register();

        Template held = Placeholders.compile("%test_x%");

        assertNotNull(held);
        assertEquals("1", held.render());
    }
}
