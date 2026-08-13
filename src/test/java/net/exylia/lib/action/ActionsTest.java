package net.exylia.lib.action;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Registry, compilation and the direct hot path. */
class ActionsTest {
    private Plugin plugin;
    private PluginActions actions;
    private ActionContext context;

    @BeforeEach void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Actions.releaseAll();
        plugin = FakeServer.newPlugin("Practice", null);
        actions = Actions.of(plugin, "practice");
        context = ActionContext.forPlayer(new FakePlayer("Steve").player())
                .origin("menu").build();
    }

    @AfterEach void tearDown() {
        Actions.releaseAll();
        FakeServer.reset();
    }

    @Test @DisplayName("a compiled click calls its handler directly with parsed arguments")
    void compileOnceExecuteDirectly() throws Exception {
        List<String> received = new ArrayList<>();
        actions.registerSync("join_queue", (ctx, args) -> {
            received.add(ctx.origin());
            received.add(args.string(0));
            return ActionResult.success();
        });
        ActionCall call = actions.compile("practice:join_queue boxing");

        ActionResult result = call.execute(context).toCompletableFuture().get();

        assertTrue(result.isSuccess());
        assertEquals(List.of("menu", "boxing"), received);
    }

    @Test @DisplayName("simple ids resolve only inside the compiling plugin namespace")
    void localIdsAreLocal() {
        actions.registerSync("open", (ctx, args) -> ActionResult.success());

        assertEquals(new ActionId("practice", "open"), actions.compile("open").id());
        assertThrows(IllegalArgumentException.class, () -> Actions.compile("open"));
    }

    @Test @DisplayName("two plugins may use the same local id without ambiguity")
    void namespacesNeverCollide() {
        Plugin other = FakeServer.newPlugin("Specials", null);
        PluginActions specials = Actions.of(other, "specials");
        actions.registerSync("open", (ctx, args) -> ActionResult.success());
        specials.registerSync("open", (ctx, args) -> ActionResult.success());

        assertEquals("practice:open", actions.compile("open").id().toString());
        assertEquals("specials:open", specials.compile("open").id().toString());
        assertEquals(2, Actions.registered());
    }

    @Test @DisplayName("none, noop and an empty value are real no-ops")
    void noopsDoNotHitTheRegistry() throws Exception {
        for (String raw : List.of("", "none", "NOOP")) {
            ActionResult result = actions.compile(raw).execute(context)
                    .toCompletableFuture().get();
            assertEquals(ActionResult.Status.STOP, result.status());
        }
        assertEquals(0, Actions.registered());
    }

    @Test @DisplayName("a duplicate full id is rejected rather than silently replacing code")
    void duplicateRegistrationFails() {
        actions.registerSync("open", (ctx, args) -> ActionResult.success());
        assertThrows(IllegalStateException.class,
                () -> actions.registerSync("open", (ctx, args) -> ActionResult.success()));
    }

    @Test @DisplayName("unregistering deactivates calls compiled by menus before disable")
    void staleCompiledCallsCannotReachADeadPlugin() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        actions.registerSync("open", (ctx, args) -> {
            runs.incrementAndGet();
            return ActionResult.success();
        });
        ActionCall retainedByOldMenu = actions.compile("open");

        assertEquals(1, actions.unregisterAll());
        ActionResult result = retainedByOldMenu.execute(context).toCompletableFuture().get();

        assertEquals(ActionResult.Status.FAILED, result.status());
        assertEquals(0, runs.get());
    }

    @Test @DisplayName("a handler exception is a failed result, never an escaped menu exception")
    void exceptionsBecomeResults() throws Exception {
        actions.registerSync("broken", (ctx, args) -> { throw new IllegalStateException("boom"); });

        ActionResult result = actions.compile("broken").execute(context)
                .toCompletableFuture().get();

        assertEquals(ActionResult.Status.FAILED, result.status());
        assertEquals("boom", result.reason());
        assertTrue(result.error() instanceof IllegalStateException);
    }

    @Test @DisplayName("quoted arguments, spaces and negative decimals parse once and correctly")
    void argumentsAreUnambiguous() {
        actions.registerSync("set", (ctx, args) -> ActionResult.success());
        ActionArguments args = actions.compile("set 'hello world' -0.5 true").arguments();

        assertEquals("hello world", args.string(0));
        assertEquals(-0.5, args.decimal(1));
        assertTrue(args.bool(2, false));
        assertEquals(99, args.integer(8, 99));
    }

    @Test @DisplayName("typed keys reject a value of the wrong type immediately")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void typedContextRejectsWrongValues() {
        ActionKey<Integer> slot = ActionKey.of("ui.slot", Integer.class);
        ActionContext valid = ActionContext.forPlayer(context.player()).put(slot, 12).build();
        assertEquals(12, valid.require(slot));

        ActionKey raw = slot;
        assertThrows(IllegalArgumentException.class,
                () -> ActionContext.forPlayer(context.player()).put(raw, "twelve"));
    }

    @Test @DisplayName("async means actual blocking work is moved, not every ordinary click")
    void asyncRegistrationUsesTasksOnlyForAsyncWork() throws Exception {
        FakeServer.runAsyncForReal();
        java.util.concurrent.atomic.AtomicReference<String> thread =
                new java.util.concurrent.atomic.AtomicReference<>();
        actions.registerAsync("load", (ctx, args) -> {
            thread.set(Thread.currentThread().getName());
            return ActionResult.success();
        });

        ActionResult result = actions.compile("load").execute(context)
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertTrue(thread.get().startsWith("FakeServer-async"), thread.get());
    }
}
