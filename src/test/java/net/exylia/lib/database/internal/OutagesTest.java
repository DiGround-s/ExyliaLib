package net.exylia.lib.database.internal;

import org.junit.jupiter.api.Test;

import java.net.SocketException;
import java.sql.SQLException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutagesTest {

    @Test
    void aDroppedSocketIsAnOutage() {
        SQLException reset = new SQLException("Connection reset", new SocketException("Connection reset"));
        assertTrue(Outages.is(new CompletionException(reset)));
    }

    @Test
    void connectionStateIsAnOutage() {
        assertTrue(Outages.is(new SQLException("Connection is closed", "08000")));
    }

    @Test
    void aBadStatementIsNot() {
        assertFalse(Outages.is(new SQLException("Unknown column", "42S22")));
    }
}
