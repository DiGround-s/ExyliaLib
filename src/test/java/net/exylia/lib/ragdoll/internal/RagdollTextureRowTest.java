package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.database.internal.Dialect;
import net.exylia.lib.database.internal.EntityModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The table a plugin keeps ragdoll skin textures in. */
class RagdollTextureRowTest {

    @Test
    @DisplayName("the row is a table every engine accepts, keyed by the piece's fingerprint")
    void everyEngineAcceptsIt() {
        EntityModel<RagdollTextureRow> model = EntityModel.of(RagdollTextureRow.class);
        assertEquals("exylia_ragdoll_skins", model.table());
        for (String engine : new String[]{"h2", "mysql", "mariadb", "postgresql"}) {
            List<String> problems = Dialect.of(engine).validate(model);
            assertTrue(problems.isEmpty(), () -> engine + " rejected the row: " + problems);
        }
    }
}
