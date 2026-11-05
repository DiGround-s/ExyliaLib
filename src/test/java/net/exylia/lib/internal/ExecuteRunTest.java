package net.exylia.lib.internal;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** {@link ExecuteRun}: a wrapped action is handed its own command's text under {@code execute ... run}. */
class ExecuteRunTest {

    /** Stands in for Lamp's action: it reads the whole input, as Lamp does. */
    private static final class Reader implements Command<Object> {
        final List<String> seen = new ArrayList<>();

        @Override
        public int run(com.mojang.brigadier.context.CommandContext<Object> context) {
            seen.add(context.getInput());
            return 1;
        }
    }

    @Test
    void underExecuteRunTheActionSeesItsOwnLine() throws Exception {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        Reader reader = new Reader();
        dispatcher.register(LiteralArgumentBuilder.literal("supplydrop").executes(reader)
                .then(LiteralArgumentBuilder.<Object>literal("here").executes(reader)));
        // The shape vanilla's /execute has: "run" redirects to the root.
        dispatcher.register(LiteralArgumentBuilder.literal("execute")
                .then(LiteralArgumentBuilder.<Object>literal("run").redirect(dispatcher.getRoot())));

        Field field = CommandNode.class.getDeclaredField("command");
        field.setAccessible(true);
        assertEquals(2, ExecuteRun.wrap(dispatcher.getRoot(), field, action -> action == reader));

        dispatcher.execute("execute run supplydrop here", new Object());
        dispatcher.execute("supplydrop here", new Object());
        assertEquals(List.of("supplydrop here", "supplydrop here"), reader.seen);
    }

    @Test
    void onlyLampActionsAreTouched() {
        Command<Object> mine = context -> 1;
        assertFalse(ExecuteRun.isLamp(mine));
    }
}
