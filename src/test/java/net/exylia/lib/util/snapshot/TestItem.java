package net.exylia.lib.util.snapshot;

import net.exylia.lib.util.snapshot.SnapshotCodec.ItemIo;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An item that exists without a server.
 *
 * <p>A real {@code ItemStack} cannot be built here: its constructor asks Paper's
 * registry for the material's item type, and that registry only exists inside a
 * running server. Which is exactly why {@link SnapshotCodec} has a seam for how
 * an item becomes text — the wire format is the one thing in this module where a
 * mistake costs a production inventory, so it has to be testable.
 *
 * <p>The stored form here is {@code item:<name>x<amount>}, which is not Base64
 * and is not meant to be. What the tests prove is the shape of the JSON around
 * it: which keys, in which places, with an empty slot as {@code null}. The
 * Base64 itself is one call to Bukkit and is identical to the one ExyliaCommons
 * made.
 */
final class TestItem extends ItemStack {

    /** The seam that reads and writes {@link TestItem}s. */
    static final ItemIo IO = new ItemIo() {

        @Override
        public boolean isEmpty(@NotNull ItemStack stack) {
            // Material.isAir() also needs the server's registry, so emptiness is
            // decided here too. An amount of zero is how a test says "nothing".
            return !(stack instanceof TestItem item) || item.amount <= 0;
        }

        @Override
        public @Nullable String encode(@NotNull ItemStack stack) {
            if (!(stack instanceof TestItem item)) {
                return null;
            }
            return "item:" + item.name + 'x' + item.amount;
        }

        @Override
        public @Nullable ItemStack decode(@NotNull String stored) {
            if (!stored.startsWith("item:")) {
                // Anything else is an item this "server" cannot read, which is
                // the case the whole per-slot recovery exists for.
                throw new IllegalArgumentException("not a test item: " + stored);
            }
            String body = stored.substring("item:".length());
            int split = body.lastIndexOf('x');
            return new TestItem(body.substring(0, split),
                    Integer.parseInt(body.substring(split + 1)));
        }
    };

    private final String name;
    private final int amount;

    TestItem(String name, int amount) {
        super();
        this.name = name;
        this.amount = amount;
    }

    static TestItem of(String name) {
        return new TestItem(name, 1);
    }

    @Override
    public int getAmount() {
        return amount;
    }

    @Override
    public @NotNull ItemStack clone() {
        // The inherited clone delegates to a Paper-side field that the protected
        // constructor never set, so it throws. A snapshot copies items in and
        // out, which means every test would fail on this and none of them would
        // be about the wire format.
        return new TestItem(name, amount);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TestItem item && item.name.equals(name) && item.amount == amount;
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + amount;
    }

    @Override
    public @NotNull String toString() {
        return "TestItem[" + name + " x" + amount + ']';
    }
}
