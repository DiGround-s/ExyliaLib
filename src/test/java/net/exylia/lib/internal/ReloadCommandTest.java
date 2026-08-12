package net.exylia.lib.internal;

import net.exylia.lib.FakeServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library's own command: what it does and what it tells the sender.
 */
class ReloadCommandTest {

    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger reloads = new AtomicInteger();
    private ReloadCommand command;
    private CommandSender sender;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();

        command = new ReloadCommand(reloads::incrementAndGet, () -> "1.14.0");
        sender = (CommandSender) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args[0] instanceof Component c) {
                        sent.add(PlainTextComponentSerializer.plainText().serialize(c));
                        return null;
                    }
                    return FakeServer.defaultValue(method.getReturnType());
                });
    }

    @AfterEach
    void tearDown() {
        FakeServer.reset();
    }

    @Test
    @DisplayName("reload actually reloads")
    void reloadRunsTheAction() {
        command.reload(sender);

        assertEquals(1, reloads.get(), "the palette reload must run");
    }

    @Test
    @DisplayName("reload tells the sender it happened")
    void reloadConfirms() {
        command.reload(sender);

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("reloaded"),
                "the sender hears the result, got: " + sent.get(0));
    }

    @Test
    @DisplayName("the confirmation carries how long it took")
    void reloadReportsDuration() {
        command.reload(sender);

        assertTrue(sent.get(0).contains("ms"), "got: " + sent.get(0));
    }

    @Test
    @DisplayName("the overview names the version and the subcommand")
    void overviewShowsVersionAndUsage() {
        command.overview(sender);

        assertEquals(1, sent.size());
        assertTrue(sent.get(0).contains("1.14.0"), "got: " + sent.get(0));
        assertTrue(sent.get(0).contains("/exylialib reload"), "got: " + sent.get(0));
    }

    @Test
    @DisplayName("the overview does not reload anything")
    void overviewDoesNotReload() {
        command.overview(sender);

        assertEquals(0, reloads.get());
    }
}
