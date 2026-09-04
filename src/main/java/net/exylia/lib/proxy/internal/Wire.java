package net.exylia.lib.proxy.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * The bytes on the {@code exylia:bridge} channel, in both directions.
 *
 * <p>A request is {@code UTF module, int id, UTF payload}; an answer is
 * {@code UTF module, int id, byte status, UTF detail}. ExyliaProxyUtils
 * reads and writes exactly this, so a change here is a change there.
 */
@ApiStatus.Internal
public final class Wire {

    /** The channel both sides register; lowercase, namespaced, as Paper requires. */
    public static final String CHANNEL = "exylia:bridge";

    private Wire() {
        throw new AssertionError("No instances.");
    }

    /** One request, as sent. */
    public record Request(@NotNull String module, int id, @NotNull String payload) {
    }

    /** One answer, as received. */
    public record Answer(@NotNull String module, int id, int status, @NotNull String detail) {
    }

    public static byte @NotNull [] encode(@NotNull Request request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeUTF(request.module());
            out.writeInt(request.id());
            out.writeUTF(request.payload());
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalArgumentException("Request too long for the wire: " + request.module(),
                    impossible);
        }
    }

    /**
     * Reads an answer.
     *
     * @throws IOException if the bytes are not one; the caller drops them
     */
    public static @NotNull Answer decode(byte @NotNull [] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        return new Answer(in.readUTF(), in.readInt(), in.readUnsignedByte(), in.readUTF());
    }
}
