package net.exylia.lib.clientmob.internal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/** Entity ids for mobs when PacketEvents is missing: far above any real id, never repeated. */
final class IdPool {

    static final int START = Integer.MAX_VALUE / 2 + 2_000_000;

    private IdPool() {
    }

    static IntSupplier counter() {
        AtomicInteger next = new AtomicInteger(START);
        return next::incrementAndGet;
    }
}
