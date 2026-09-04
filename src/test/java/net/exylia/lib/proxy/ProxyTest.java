package net.exylia.lib.proxy;

import net.exylia.lib.FakePlayer;
import net.exylia.lib.FakeServer;
import net.exylia.lib.command.CommandResult;
import net.exylia.lib.proxy.internal.BridgeCommands;
import net.exylia.lib.proxy.internal.Wire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyTest {

    @BeforeEach
    void server() {
        FakeServer.install();
        FakeServer.reset();
    }

    @Test
    @DisplayName("a request is UTF module, int id, UTF payload — what ExyliaProxyUtils reads")
    void requestWire() throws IOException {
        byte[] bytes = Wire.encode(new Wire.Request("commands", 7, "player-proxy:server lobby"));
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals("commands", in.readUTF());
        assertEquals(7, in.readInt());
        assertEquals("player-proxy:server lobby", in.readUTF());
        assertEquals(0, in.available(), "nothing trails the payload");
    }

    @Test
    @DisplayName("an answer is UTF module, int id, byte status, UTF detail — what ExyliaProxyUtils writes")
    void answerWire() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF("ping");
        out.writeInt(3);
        out.writeByte(0);
        out.writeUTF("ExyliaProxyUtils 1.0.0 on Velocity");
        Wire.Answer answer = Wire.decode(bytes.toByteArray());
        assertEquals(new Wire.Answer("ping", 3, 0, "ExyliaProxyUtils 1.0.0 on Velocity"), answer);
        assertTrue(ProxyReply.ofWire(answer.status(), answer.detail()).isOk());
        assertEquals(ProxyReply.Status.FAILED, ProxyReply.ofWire(200, "?").status(),
                "a status this side does not know is a failure, never a success");
    }

    @Test
    @DisplayName("without the channel registered a request says NO_BRIDGE at once")
    void noRuntimeIsHonest() {
        FakePlayer steve = new FakePlayer("Steve");
        ProxyReply reply = Proxy.request(steve.player(), "commands", "console-proxy:alert hi").join();
        assertEquals(ProxyReply.Status.NO_BRIDGE, reply.status());
        assertFalse(reply.reachedProxy());
        assertFalse(Proxy.isAvailable());
    }

    @Test
    @DisplayName("every reply status maps to a command result, and only OK continues a list")
    void replyToResult() {
        for (ProxyReply.Status status : ProxyReply.Status.values()) {
            CommandResult result = toResult(new ProxyReply(status, "why"));
            assertEquals(status == ProxyReply.Status.OK, result.continues(), status.name());
        }
        assertEquals(CommandResult.Status.NO_TRANSPORT,
                toResult(new ProxyReply(ProxyReply.Status.TIMEOUT, "silent")).status());
        assertEquals(CommandResult.Status.REJECTED,
                toResult(new ProxyReply(ProxyReply.Status.REJECTED, "no such command")).status());
    }

    private static CommandResult toResult(ProxyReply reply) {
        return BridgeCommands.toResult(reply, "x");
    }
}
