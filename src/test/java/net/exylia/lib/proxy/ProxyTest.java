package net.exylia.lib.proxy;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.command.CommandResult;
import net.exylia.lib.proxy.internal.BridgeCommands;
import net.exylia.lib.proxy.internal.Frames;
import net.exylia.lib.proxy.internal.ProxyRuntime;
import net.exylia.lib.util.teleport.internal.CrossServer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyTest {

    private static final UUID STEVE = UUID.nameUUIDFromBytes("steve".getBytes());

    @BeforeEach
    void server() {
        FakeServer.install();
        FakeServer.reset();
    }

    @Test
    @DisplayName("this server's name comes from the plugin with Redis on, before the proxy answers, and never changes")
    void serverNameIsStable(@TempDir Path folder) throws IOException {
        Path staff = Files.createDirectories(folder.resolve("Staff"));
        Path core = Files.createDirectories(folder.resolve("Core"));
        Files.writeString(core.resolve("database.yml"), "database:\n  redis:\n    enabled: false\n");
        Plugin corePlugin = FakeServer.newPlugin("Core", core.toFile());
        FakeServer.plugins(corePlugin, FakeServer.newPlugin("Staff", staff.toFile()));
        ProxyRuntime.shutdown();
        try {
            assertEquals("server-1", CrossServer.serverId(corePlugin), "no Redis anywhere: the plugin's own name");

            Files.writeString(staff.resolve("database.yml"),
                    "database:\n  redis:\n    enabled: true\n    server-id: survival\n");
            ProxyRuntime.shutdown();
            assertFalse(Proxy.isAvailable());
            assertEquals("survival", CrossServer.serverId(corePlugin),
                    "the bridge's name, with no proxy answering yet");

            Files.writeString(staff.resolve("database.yml"),
                    "database:\n  redis:\n    enabled: true\n    server-id: other\n");
            assertEquals("survival", CrossServer.serverId(corePlugin), "picked once for the life of the server");
        } finally {
            ProxyRuntime.shutdown();
        }
    }

    @Test
    @DisplayName("a request is server|carrier|module|id|payload — what ExyliaProxyUtils reads")
    void requestFrame() {
        assertEquals("lobby|" + STEVE + "|commands|7|player-proxy:server a|b",
                Frames.request("lobby", STEVE, "commands", 7, "player-proxy:server a|b"));
        assertEquals("lobby||players|8|", Frames.request("lobby", null, "players", 8, ""));
        assertEquals("exylia:bridge:proxy", Frames.channelOf("exylia", Frames.PROXY));
    }

    @Test
    @DisplayName("an answer is module|id|status|carrier|detail — what ExyliaProxyUtils writes")
    void answerFrame() {
        Frames.Answer answer = Frames.decode("ping|3|0||ExyliaProxyUtils 2.0.0 on Velocity");
        assertEquals(new Frames.Answer("ping", 3, 0, null, "ExyliaProxyUtils 2.0.0 on Velocity"), answer);
        assertTrue(ProxyReply.ofWire(answer.status(), answer.detail()).isOk());
        Frames.Answer push = Frames.decode("arrive|0|0|" + STEVE + "|lobby,world,1,2,3|x");
        assertEquals(STEVE, push.carrier());
        assertEquals("lobby,world,1,2,3|x", push.detail(), "the detail keeps its pipes");
        assertEquals(ProxyReply.Status.FAILED, ProxyReply.ofWire(200, "?").status(),
                "a status this side does not know is a failure, never a success");
        assertThrows(IllegalArgumentException.class, () -> Frames.decode("garbage"));
    }

    @Test
    @DisplayName("without Redis a request says NO_BRIDGE at once")
    void noRuntimeIsHonest() {
        FakePlayer steve = new FakePlayer("Steve");
        ProxyReply reply = Proxy.request(steve.player(), "commands", "console-proxy:alert hi").join();
        assertEquals(ProxyReply.Status.NO_BRIDGE, reply.status());
        assertFalse(reply.reachedProxy());
        assertFalse(Proxy.isAvailable());
        assertTrue(Proxy.find("Steve").join().isEmpty());
    }

    @Test
    @DisplayName("every reply status maps to a command result, and only OK continues a list")
    void replyToResult() {
        for (ProxyReply.Status status : ProxyReply.Status.values()) {
            CommandResult result = BridgeCommands.toResult(new ProxyReply(status, "why"), "x");
            assertEquals(status == ProxyReply.Status.OK, result.continues(), status.name());
        }
        assertEquals(CommandResult.Status.NO_TRANSPORT,
                BridgeCommands.toResult(new ProxyReply(ProxyReply.Status.TIMEOUT, "silent"), "x").status());
        assertEquals(CommandResult.Status.REJECTED,
                BridgeCommands.toResult(new ProxyReply(ProxyReply.Status.REJECTED, "no such command"), "x").status());
    }
}
