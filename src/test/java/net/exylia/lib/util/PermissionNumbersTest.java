package net.exylia.lib.util;

import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionNumbersTest {

    @Test
    void highestAndLowestSkipDeniedAndNonNumbers() {
        Permissible player = holding(Map.of("p.cd.30", true, "p.cd.10", true, "p.cd.2", false, "p.cd.vip", true));
        assertEquals(30, PermissionNumbers.highest(player, "p.cd.", 5));
        assertEquals(10, PermissionNumbers.lowest(player, "p.cd.", 5));
        assertEquals(5, PermissionNumbers.lowest(player, "p.other.", 5));
    }

    private static Permissible holding(Map<String, Boolean> nodes) {
        Permissible[] self = new Permissible[1];
        self[0] = (Permissible) Proxy.newProxyInstance(Permissible.class.getClassLoader(), new Class<?>[]{Permissible.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getEffectivePermissions")) throw new UnsupportedOperationException(method.getName());
                    Set<PermissionAttachmentInfo> infos = new HashSet<>();
                    nodes.forEach((node, value) -> infos.add(new PermissionAttachmentInfo(self[0], node, null, value)));
                    return infos;
                });
        return self[0];
    }
}
