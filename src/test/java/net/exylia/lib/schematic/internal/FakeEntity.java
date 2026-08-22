package net.exylia.lib.schematic.internal;

import net.exylia.lib.FakeServer;
import org.bukkit.entity.Entity;

import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * A loose entity that records whether it was removed.
 *
 * <p>Enough to prove the clear stage happened and that it left players alone,
 * which is the only thing the module decides about entities.
 */
final class FakeEntity {

    private final UUID id = UUID.randomUUID();
    private final Entity proxy;
    private volatile boolean removed;

    FakeEntity() {
        this.proxy = (Entity) Proxy.newProxyInstance(
                FakeEntity.class.getClassLoader(),
                new Class<?>[]{Entity.class},
                (self, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "remove" -> {
                        removed = true;
                        yield null;
                    }
                    case "isValid" -> !removed;
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "FakeEntity";
                    default -> FakeServer.defaultValue(method.getReturnType());
                });
    }

    /** The entity to hand to the code under test. */
    Entity entity() {
        return proxy;
    }

    /** Whether the clear stage removed it. */
    boolean isRemoved() {
        return removed;
    }
}
