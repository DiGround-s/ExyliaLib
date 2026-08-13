package net.exylia.lib.action;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.placeholder.Placeholders;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two things a menu needs from actions and could not have before: a button
 * whose action depends on the row it is drawn for, and a way to stop what has
 * not run yet when the screen goes away.
 */
class ActionTemplateTest {

    private Plugin plugin;
    private PluginActions actions;
    private FakePlayer viewer;
    private ActionContext context;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Actions.releaseAll();
        plugin = FakeServer.newPlugin("Practice", null);
        actions = Actions.of(plugin, "practice");
        viewer = new FakePlayer("Steve");
        context = ActionContext.forPlayer(viewer.player()).origin("menu").build();
    }

    @AfterEach
    void tearDown() {
        Actions.releaseAll();
        Placeholders.unregisterAll("Practice");
        FakeServer.reset();
    }

    @Test
    @DisplayName("a static action string is compiled once, not on every use")
    void staticTemplatesResolveToTheSameCall() {
        actions.registerSync("kick", (ctx, args) -> ActionResult.success());
        ActionTemplate template = actions.template("practice:kick");

        assertFalse(template.isDynamic());
        assertSameCall(template);
    }

    private void assertSameCall(ActionTemplate template) {
        ActionCall first = template.resolve(viewer.player());
        ActionCall second = template.resolve(viewer.player());
        assertTrue(first == second, "a static template should not recompile");
    }

    @Test
    @DisplayName("a mistyped static action is reported when the menu loads, not when clicked")
    void staticTemplatesFailEarly() {
        assertThrows(IllegalArgumentException.class,
                () -> actions.template("practice:does_not_exist"));
    }

    @Test
    @DisplayName("each row resolves the action for the thing in that row")
    void dynamicTemplatesResolvePerRow() throws Exception {
        Placeholders.register(plugin, "member_id", request ->
                request.get("member_id", Integer.class, 0));
        AtomicInteger kicked = new AtomicInteger();
        actions.registerSync("kick", (ctx, args) -> {
            kicked.set(args.integer(0));
            return ActionResult.success();
        });
        ActionTemplate template = actions.template("practice:kick %member_id%");

        assertTrue(template.isDynamic());
        template.resolve(viewer.player(), Map.of("member_id", 7))
                .execute(context).toCompletableFuture().get();

        assertEquals(7, kicked.get());
    }

    @Test
    @DisplayName("a row with nothing to offer resolves to a no-op, not an error")
    void dynamicTemplatesCanResolveToNothing() throws Exception {
        Placeholders.register(plugin, "row_action", request ->
                request.get("row_action", String.class, "none"));
        actions.registerSync("kick", (ctx, args) -> ActionResult.success());
        ActionTemplate template = actions.template("%row_action%");

        ActionResult result = template.resolve(viewer.player(), Map.of("row_action", "none"))
                .execute(context).toCompletableFuture().get();

        assertEquals(ActionResult.Status.STOP, result.status());
    }

    @Test
    @DisplayName("an action that no longer exists leaves a dead button, not a broken menu")
    void resolveOrNoopSurvivesStaleData() throws Exception {
        Placeholders.register(plugin, "row_action", request ->
                request.get("row_action", String.class, "none"));
        ActionTemplate template = actions.template("%row_action%");

        ActionResult result = template
                .resolveOrNoop(viewer.player(), Map.of("row_action", "practice:deleted_action"))
                .execute(context).toCompletableFuture().get();

        assertEquals(ActionResult.Status.STOP, result.status());
    }

    @Test
    @DisplayName("cancelling stops the steps that have not run yet")
    void cancellingStopsTheRest() {
        AtomicInteger runs = new AtomicInteger();
        actions.registerSync("step", (ctx, args) -> {
            runs.incrementAndGet();
            return ActionResult.success();
        });
        ActionExecution execution = actions.sequence()
                .then("step")
                .then("step", 10)
                .build()
                .execute(context);

        assertEquals(1, runs.get(), "the immediate step ran");
        assertTrue(execution.cancel());

        FakeServer.tick(20);
        assertEquals(1, runs.get(), "the delayed step must not run after cancelling");
        assertTrue(execution.isCancelled());
        assertTrue(execution.isDone());
    }

    @Test
    @DisplayName("cancelling leaves no live task behind")
    void cancellingCancelsThePendingDelay() {
        actions.registerSync("step", (ctx, args) -> ActionResult.success());
        ActionExecution execution = actions.sequence().then("step", 40).build().execute(context);

        assertEquals(1, FakeServer.liveTasks(), "the delay is waiting");
        execution.cancel();

        assertEquals(0, FakeServer.liveTasks(), "a cancelled sequence leaves nothing running");
    }

    @Test
    @DisplayName("a step finishing after the menu closed does not start the next one")
    void cancellingDuringAnAsyncStepStopsTheChain() throws Exception {
        FakeServer.runAsyncForReal();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger after = new AtomicInteger();
        actions.registerAsync("slow", (ctx, args) -> {
            started.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ActionResult.success();
        });
        actions.registerSync("after", (ctx, args) -> {
            after.incrementAndGet();
            return ActionResult.success();
        });

        ActionExecution execution = actions.compile(java.util.List.of("slow", "after"))
                .execute(context);
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));

        // The player closed the menu while the lookup was still running.
        execution.cancel("menu closed");
        release.countDown();

        ActionResult result = execution.result().get();
        assertEquals(ActionResult.Status.STOP, result.status());
        Thread.sleep(100);
        assertEquals(0, after.get(),
                "the next step must not run once the sequence was cancelled");
    }

    @Test
    @DisplayName("cancelling twice is harmless")
    void cancellingIsIdempotent() throws Exception {
        actions.registerSync("step", (ctx, args) -> ActionResult.success());
        ActionExecution execution = actions.sequence().then("step", 10).build().execute(context);

        assertTrue(execution.cancel("menu closed"));
        assertFalse(execution.cancel("again"));

        ActionResult result = execution.result().get();
        assertEquals(ActionResult.Status.STOP, result.status());
        assertEquals("menu closed", result.reason());
    }

    @Test
    @DisplayName("a finished sequence cannot be cancelled into a different answer")
    void cancellingAfterCompletionKeepsTheRealResult() throws Exception {
        actions.registerSync("step", (ctx, args) -> ActionResult.success());
        ActionExecution execution = actions.compile(java.util.List.of("step")).execute(context);

        assertTrue(execution.result().get().isSuccess());
        execution.cancel();

        assertTrue(execution.result().get().isSuccess(),
                "the sequence already finished; cancelling must not rewrite it");
    }
}
