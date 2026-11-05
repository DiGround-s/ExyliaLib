package net.exylia.lib.database.internal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TypedBindTest {

    @Test
    void storedTypesNeverReachSetObject() throws Exception {
        List<String> calls = new ArrayList<>();
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    return null;
                });
        Object[] values = {"a", 1L, 2, 3.0, true, (short) 4, (byte) 5, 6f, BigDecimal.ONE, new byte[]{7}};
        for (int i = 0; i < values.length; i++) SqlBackend.setValue(statement, i + 1, values[i]);
        assertEquals(List.of("setString", "setLong", "setInt", "setDouble", "setBoolean", "setShort",
                "setByte", "setFloat", "setBigDecimal", "setBytes"), calls);
    }
}
