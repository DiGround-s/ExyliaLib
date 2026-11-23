package net.exylia.lib.web.internal;

import com.google.gson.JsonObject;
import net.exylia.lib.web.EntryStore;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A new entry in the browser starts from the record's own defaults, so the schema carries them. */
class EntrySourceDefaultsTest {

    record Product(String name, boolean enabled, int stock, String price) {
        Product() {
            this("", true, -1, "off");
        }
    }

    private static final EntryStore<Product> STORE = new EntryStore<>() {
        @Override
        public @NotNull Map<String, Product> entries() {
            return Map.of();
        }

        @Override
        public @NotNull CompletableFuture<Void> save(@NotNull String id, @NotNull Product value, boolean created) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NotNull CompletableFuture<Void> delete(@NotNull String id) {
            return CompletableFuture.completedFuture(null);
        }
    };

    @Test
    void everyFieldCarriesItsDefault() {
        JsonObject schema = new EntrySource<>("products", Product.class, STORE).schema("Test");
        JsonObject entry = schema.getAsJsonArray("fields").get(0).getAsJsonObject()
                .getAsJsonObject("type").getAsJsonObject("of");
        Map<String, String> defaults = new java.util.HashMap<>();
        entry.getAsJsonArray("fields").forEach(element -> {
            JsonObject field = element.getAsJsonObject();
            assertTrue(field.has("default"), field.get("key").getAsString() + " has no default");
            defaults.put(field.get("key").getAsString(), field.get("default").getAsString());
        });
        assertEquals("", defaults.get("name"));
        assertEquals("true", defaults.get("enabled"));
        assertEquals("-1", defaults.get("stock"));
        assertEquals("off", defaults.get("price"));
    }
}
