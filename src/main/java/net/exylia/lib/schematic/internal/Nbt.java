package net.exylia.lib.schematic.internal;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Named Binary Tag, read and written by hand.
 *
 * <p>All twelve tag types, not a schematic-shaped subset: a block entity's data
 * is whatever vanilla wrote, and an item component can hold a long array or a
 * float list. Dropping a type here would not fail loudly, it would fail as a
 * chest that came back missing the one enchanted sword in it.
 *
 * <p>Values are plain Java: {@code Byte}, {@code Short}, {@code Integer},
 * {@code Long}, {@code Float}, {@code Double}, {@code byte[]}, {@code String},
 * {@link ListTag}, {@code Map<String, Object>} (insertion ordered) for a
 * compound, {@code int[]} and {@code long[]}. No tag class hierarchy, because
 * nothing here is generic enough to need one, and no dependency, because every
 * one of them gets installed on every server.
 *
 * <h2>Reading untrusted bytes</h2>
 * A schematic is a file somebody dropped in a folder. Every allocation is
 * charged against a byte budget before it is made, and nesting is capped at the
 * depth vanilla allows, so a crafted file is refused with an
 * {@link IOException} rather than taking the heap or the stack with it. A list
 * of four billion empty compounds costs its declared size up front, not after
 * the {@code ArrayList} grew to hold them.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class Nbt {

    static final int END = 0;
    static final int BYTE = 1;
    static final int SHORT = 2;
    static final int INT = 3;
    static final int LONG = 4;
    static final int FLOAT = 5;
    static final int DOUBLE = 6;
    static final int BYTE_ARRAY = 7;
    static final int STRING = 8;
    static final int LIST = 9;
    static final int COMPOUND = 10;
    static final int INT_ARRAY = 11;
    static final int LONG_ARRAY = 12;

    /** The nesting vanilla itself refuses past. */
    static final int MAX_DEPTH = 512;

    /**
     * The budget for one item or one entity: vanilla caps a whole chunk's
     * worth of network NBT at two megabytes, so a single item past this is not
     * an item.
     */
    static final long SMALL_BUDGET = 8L << 20;

    private Nbt() {
        throw new AssertionError("No instances.");
    }

    /**
     * A list tag: NBT lists are typed, and an empty one still says of what.
     *
     * @param type  the element tag type, {@link #END} only when empty
     * @param items the elements, each of that type
     */
    record ListTag(int type, @NotNull List<Object> items) {
    }

    /** A root tag: its name and its compound. */
    record Root(@NotNull String name, @NotNull Map<String, Object> value) {
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Reads one named root compound.
     *
     * @param in     the uncompressed stream
     * @param budget the most bytes the result may occupy, roughly
     * @return the root
     * @throws IOException when the bytes are not NBT, or are larger or deeper
     *                     than allowed
     */
    static @NotNull Root read(@NotNull DataInput in, long budget) throws IOException {
        Reader reader = new Reader(in, budget);
        int type = in.readByte();
        if (type != COMPOUND) {
            throw new IOException("the root tag is not a compound (type " + type + ")");
        }
        String name = reader.string();
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) reader.payload(COMPOUND, 0);
        return new Root(name, value);
    }

    /** Reads a gzipped root compound, as Paper serialises items and entities. */
    public static @NotNull Map<String, Object> gunzip(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            return read(in, SMALL_BUDGET).value();
        }
    }

    private static final class Reader {

        private final DataInput in;
        private long budget;

        private Reader(DataInput in, long budget) {
            this.in = in;
            this.budget = budget;
        }

        private void charge(long bytes) throws IOException {
            budget -= bytes;
            if (budget < 0) {
                throw new IOException("the NBT is larger than allowed");
            }
        }

        private int length() throws IOException {
            int length = in.readInt();
            if (length < 0) {
                throw new IOException("negative length " + length);
            }
            return length;
        }

        private String string() throws IOException {
            String value = in.readUTF();
            charge(40L + 2L * value.length());
            return value;
        }

        private Object payload(int type, int depth) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("the NBT is nested deeper than " + MAX_DEPTH);
            }
            switch (type) {
                case BYTE -> {
                    charge(1);
                    return in.readByte();
                }
                case SHORT -> {
                    charge(2);
                    return in.readShort();
                }
                case INT -> {
                    charge(4);
                    return in.readInt();
                }
                case LONG -> {
                    charge(8);
                    return in.readLong();
                }
                case FLOAT -> {
                    charge(4);
                    return in.readFloat();
                }
                case DOUBLE -> {
                    charge(8);
                    return in.readDouble();
                }
                case BYTE_ARRAY -> {
                    int length = length();
                    charge(16L + length);
                    byte[] value = new byte[length];
                    in.readFully(value);
                    return value;
                }
                case STRING -> {
                    return string();
                }
                case LIST -> {
                    int elementType = in.readByte();
                    int length = length();
                    if (length > 0 && (elementType <= END || elementType > LONG_ARRAY)) {
                        throw new IOException("a list of unknown tag type " + elementType);
                    }
                    // Up front, before a single element: the reference costs
                    // whatever the element does not.
                    charge(16L + 8L * length);
                    List<Object> items = new ArrayList<>(Math.min(length, 1024));
                    for (int i = 0; i < length; i++) {
                        items.add(payload(elementType, depth + 1));
                    }
                    return new ListTag(length == 0 ? END : elementType, items);
                }
                case COMPOUND -> {
                    charge(48);
                    Map<String, Object> value = new LinkedHashMap<>();
                    while (true) {
                        int entryType = in.readByte();
                        if (entryType == END) {
                            return value;
                        }
                        String name = string();
                        charge(32);
                        value.put(name, payload(entryType, depth + 1));
                    }
                }
                case INT_ARRAY -> {
                    int length = length();
                    charge(16L + 4L * length);
                    int[] value = new int[length];
                    for (int i = 0; i < length; i++) {
                        value[i] = in.readInt();
                    }
                    return value;
                }
                case LONG_ARRAY -> {
                    int length = length();
                    charge(16L + 8L * length);
                    long[] value = new long[length];
                    for (int i = 0; i < length; i++) {
                        value[i] = in.readLong();
                    }
                    return value;
                }
                default -> throw new IOException("unknown tag type " + type);
            }
        }
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Writes one named root compound.
     *
     * @param out  the uncompressed stream
     * @param name the root's name
     * @param root the compound
     * @throws IOException when the stream fails
     * @throws IllegalArgumentException when a value is not one of the types
     *                                  this class reads back
     */
    static void write(@NotNull DataOutput out, @NotNull String name,
                      @NotNull Map<String, Object> root) throws IOException {
        out.writeByte(COMPOUND);
        out.writeUTF(name);
        payload(out, root);
    }

    /** Writes a gzipped root compound, as Paper deserialises items and entities. */
    public static byte[] gzip(@NotNull Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            write(out, "", root);
        }
        return bytes.toByteArray();
    }

    /** The tag type a Java value is written as. */
    static int typeOf(Object value) {
        if (value instanceof Byte) return BYTE;
        if (value instanceof Short) return SHORT;
        if (value instanceof Integer) return INT;
        if (value instanceof Long) return LONG;
        if (value instanceof Float) return FLOAT;
        if (value instanceof Double) return DOUBLE;
        if (value instanceof byte[]) return BYTE_ARRAY;
        if (value instanceof String) return STRING;
        if (value instanceof ListTag) return LIST;
        if (value instanceof Map<?, ?>) return COMPOUND;
        if (value instanceof int[]) return INT_ARRAY;
        if (value instanceof long[]) return LONG_ARRAY;
        throw new IllegalArgumentException("not an NBT value: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static void payload(DataOutput out, Object value) throws IOException {
        switch (typeOf(value)) {
            case BYTE -> out.writeByte((Byte) value);
            case SHORT -> out.writeShort((Short) value);
            case INT -> out.writeInt((Integer) value);
            case LONG -> out.writeLong((Long) value);
            case FLOAT -> out.writeFloat((Float) value);
            case DOUBLE -> out.writeDouble((Double) value);
            case BYTE_ARRAY -> {
                byte[] bytes = (byte[]) value;
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            case STRING -> out.writeUTF((String) value);
            case LIST -> {
                ListTag list = (ListTag) value;
                out.writeByte(list.items().isEmpty() ? END : list.type());
                out.writeInt(list.items().size());
                for (Object item : list.items()) {
                    if (typeOf(item) != list.type()) {
                        throw new IllegalArgumentException("a list of type " + list.type()
                                + " holds a " + typeOf(item));
                    }
                    payload(out, item);
                }
            }
            case COMPOUND -> {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    out.writeByte(typeOf(entry.getValue()));
                    out.writeUTF((String) entry.getKey());
                    payload(out, entry.getValue());
                }
                out.writeByte(END);
            }
            case INT_ARRAY -> {
                int[] ints = (int[]) value;
                out.writeInt(ints.length);
                for (int i : ints) out.writeInt(i);
            }
            case LONG_ARRAY -> {
                long[] longs = (long[]) value;
                out.writeInt(longs.length);
                for (long l : longs) out.writeLong(l);
            }
            default -> throw new IllegalStateException();
        }
    }
}
