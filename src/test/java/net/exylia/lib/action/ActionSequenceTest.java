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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sequential execution, stops, delays and shared typed variables. */
class ActionSequenceTest {
    private PluginActions actions;
    private ActionContext context;

    @BeforeEach void setUp() {
        FakeServer.install();
        FakeServer.reset();
        Actions.releaseAll();
        Plugin plugin = FakeServer.newPlugin("Specials", null);
        actions = Actions.of(plugin, "specials");
        context = ActionContext.forPlayer(new FakePlayer("Steve").player())
                .origin("item").build();
    }

    @AfterEach void tearDown() {
        Actions.releaseAll();
        FakeServer.reset();
    }

    @Test @DisplayName("steps run in order and share a typed scope")
    void sequenceSharesValues() throws Exception {
        ActionKey<Double> lastDamage = ActionKey.of("specials.last_damage", Double.class);
        List<String> order = new ArrayList<>();
        actions.registerSync("damage", (ctx, args) -> {
            order.add("damage");
            ctx.scope().set(lastDamage, args.decimal(0));
            return ActionResult.success();
        });
        actions.registerSync("heal", (ctx, args) -> {
            order.add("heal:" + ctx.scope().require(lastDamage));
            return ActionResult.success();
        });
        ActionSequence sequence = actions.compile(List.of("damage 3.5", "heal"));

        ActionResult result = sequence.execute(context).get();

        assertTrue(result.isSuccess());
        assertEquals(List.of("damage", "heal:3.5"), order);
    }

    @Test @DisplayName("each structured step can bind its own typed config record")
    void stepsBindTypedConfiguration() throws Exception {
        record DamageConfig(double amount) { }
        ActionKey<DamageConfig> config = ActionKey.of("specials.damage_config", DamageConfig.class);
        List<Double> seen = new ArrayList<>();
        actions.registerSync("damage_configured", (ctx, args) -> {
            seen.add(ctx.scope().require(config).amount());
            return ActionResult.success();
        });
        ActionSequence sequence = actions.sequence()
                .then("damage_configured", 0, config, new DamageConfig(3.0))
                .then("damage_configured", 0, config, new DamageConfig(8.5))
                .build();

        assertTrue(sequence.execute(context).get().isSuccess());
        assertEquals(List.of(3.0, 8.5), seen);
    }

    @Test @DisplayName("STOP is intentional and prevents the remaining steps")
    void stopStopsTheChain() throws Exception {
        AtomicInteger after = new AtomicInteger();
        actions.registerSync("condition", (ctx, args) -> ActionResult.stop("not applicable"));
        actions.registerSync("after", (ctx, args) -> {
            after.incrementAndGet();
            return ActionResult.success();
        });

        ActionResult result = actions.compile(List.of("condition", "after"))
                .execute(context).get();

        assertEquals(ActionResult.Status.STOP, result.status());
        assertEquals(0, after.get());
    }

    @Test @DisplayName("DENIED and FAILED also stop rather than running effects after failure")
    void nonSuccessStopsTheChain() throws Exception {
        for (ActionResult blocked : List.of(
                ActionResult.denied("permission"), ActionResult.failed("bad input"))) {
            AtomicInteger after = new AtomicInteger();
            String id = blocked.status().name().toLowerCase();
            actions.registerSync(id, (ctx, args) -> blocked);
            actions.registerSync("after_" + id, (ctx, args) -> {
                after.incrementAndGet();
                return ActionResult.success();
            });
            ActionResult result = actions.compile(List.of(id, "after_" + id))
                    .execute(context).get();
            assertEquals(blocked.status(), result.status());
            assertEquals(0, after.get());
        }
    }

    @Test @DisplayName("a delayed step does not run until its entity timer reaches it")
    void delayedStepsUseThePlayerScheduler() {
        AtomicInteger runs = new AtomicInteger();
        actions.registerSync("later", (ctx, args) -> {
            runs.incrementAndGet();
            return ActionResult.success();
        });
        ActionSequence sequence = actions.sequence().then("later", 5).build();

        var result = sequence.execute(context);
        assertEquals(0, runs.get());
        assertFalse(result.isDone());

        FakeServer.tick(5);
        assertEquals(0, runs.get());
        FakeServer.tick(1);
        assertEquals(1, runs.get());
        assertTrue(result.isDone());
    }

    @Test @DisplayName("an empty sequence succeeds without scheduling")
    void emptySequenceIsFree() throws Exception {
        ActionSequence sequence = actions.compile(List.of());

        ActionResult result = sequence.execute(context).get(1, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals(0, FakeServer.liveTasks());
    }
}
