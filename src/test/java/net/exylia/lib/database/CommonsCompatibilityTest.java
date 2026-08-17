package net.exylia.lib.database;

import net.exylia.lib.FakeServer;
import net.exylia.lib.database.internal.Codecs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stored formats ExyliaCommons wrote, read back byte for byte.
 *
 * <p>This is the test that decides whether a server can swap libraries. There
 * are ninety-six tables of live data out there in these formats; a change in
 * any of them is not a bug that shows up in a log, it is a kit that comes back
 * empty, a spawn that moved, or a leaderboard that resets.
 *
 * <p>Each case below carries a string produced by the Commons serialiser, and
 * asserts that this library reads it and writes the same thing back.
 */
class CommonsCompatibilityTest {

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
    }

    // ------------------------------------------------------------- Location

    @Test
    @DisplayName("a Location keeps the exact shape Commons wrote")
    void locationFormat() {
        // Commons: String.format("%s,%.2f,%.2f,%.2f,%.2f,%.2f", world, x, y, z, yaw, pitch)
        org.bukkit.World world = FakeServer.newWorld("arena");
        FakeServer.worlds(world);
        org.bukkit.Location location = new org.bukkit.Location(world, 10.5, 64.0, -20.25, 90.0f, -45.0f);

        Codec<org.bukkit.Location> codec = Codecs.builtIn(org.bukkit.Location.class);
        assertNotNull(codec, "a Location must have a built-in codec");

        assertEquals("arena,10.50,64.00,-20.25,90.00,-45.00", codec.encode(location));
    }

    @Test
    @DisplayName("a Location Commons wrote reads back to the same place")
    void locationRoundTripFromCommons() {
        org.bukkit.World world = FakeServer.newWorld("spawn");
        FakeServer.worlds(world);

        // Taken from a real stored row.
        org.bukkit.Location read = Codecs.builtIn(org.bukkit.Location.class)
                .decode("spawn,128.50,72.00,-64.25,180.00,0.00");

        assertNotNull(read);
        assertEquals(128.5, read.getX());
        assertEquals(72.0, read.getY());
        assertEquals(-64.25, read.getZ());
        assertEquals(180.0f, read.getYaw());
        assertEquals(0.0f, read.getPitch());
    }

    @Test
    @DisplayName("a Location with only coordinates still reads, as Commons allowed")
    void locationWithoutRotation() {
        // Commons accepted four parts and defaulted yaw and pitch. Rows written
        // by an older version of it look exactly like this.
        org.bukkit.World world = FakeServer.newWorld("world");
        FakeServer.worlds(world);

        org.bukkit.Location read = Codecs.builtIn(org.bukkit.Location.class)
                .decode("world,0.00,64.00,0.00");

        assertNotNull(read);
        assertEquals(0.0f, read.getYaw());
        assertEquals(0.0f, read.getPitch());
    }

    @Test
    @DisplayName("a Location is written in a fixed locale, unlike Commons")
    void locationIsLocaleIndependent() {
        // The one deliberate difference. Commons used the default locale, so a
        // server running under es_ES wrote "world,10,50,64,00,..." — a string
        // whose commas are both separators and decimal points, and which reads
        // back as a different place or not at all. This is a bug fix, not a
        // format change: rows written that way were already unreadable.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"));
            org.bukkit.World world = FakeServer.newWorld("world");
            FakeServer.worlds(world);

            String written = Codecs.builtIn(org.bukkit.Location.class)
                    .encode(new org.bukkit.Location(world, 10.5, 64.0, 0.0, 0f, 0f));

            assertEquals("world,10.50,64.00,0.00,0.00,0.00", written,
                    "a decimal comma here would corrupt every location on the server");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("a Location whose world is not loaded reads as absent, not as broken")
    void locationWithMissingWorld() {
        // Commons returned null here too. The alternative — a Location with a
        // null world — is the value that throws several frames from the cause.
        assertNull(Codecs.builtIn(org.bukkit.Location.class)
                .decode("a_world_nobody_loaded,0.00,0.00,0.00,0.00,0.00"));
    }

    @Test
    @DisplayName("a world renamed between capitalisations still resolves, as Commons did")
    void locationWorldIsCaseInsensitive() {
        org.bukkit.World world = FakeServer.newWorld("Arena");
        FakeServer.worlds(world);

        assertNotNull(Codecs.builtIn(org.bukkit.Location.class)
                .decode("arena,0.00,64.00,0.00,0.00,0.00"));
    }

    // ----------------------------------------------------------------- UUID

    @Test
    @DisplayName("a UUID is stored as its 36-character string, as Commons did")
    void uuidFormat() {
        UUID id = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5",
                Codecs.builtIn(UUID.class).encode(id));
        assertEquals(id, Codecs.builtIn(UUID.class).decode("069a79f4-44e9-4726-a5be-fca90e38aaf5"));
    }

    @Test
    @DisplayName("an unreadable UUID is absent rather than an exception")
    void malformedUuid() {
        // One corrupted row must not fail the load that would have reported it.
        assertNull(Codecs.builtIn(UUID.class).decode("not-a-uuid"));
    }

    // ----------------------------------------------------------------- enums

    @Test
    @DisplayName("an enum is stored by name, never by ordinal")
    void enumFormat() {
        // Reordering an enum's constants is a normal refactor. With ordinals it
        // silently reinterprets every stored row as a different value, which is
        // the kind of bug that turns a survival arena into a creative one.
        Codec<Sample> codec = Codecs.builtIn(Sample.class);
        assertNotNull(codec);

        assertEquals("SECOND", codec.encode(Sample.SECOND));
        assertEquals(Sample.SECOND, codec.decode("SECOND"));
    }

    @Test
    @DisplayName("an enum constant that no longer exists reads as absent")
    void removedEnumConstant() {
        assertNull(Codecs.builtIn(Sample.class).decode("A_MODE_SOMEBODY_DELETED"));
    }

    private enum Sample {
        FIRST,
        SECOND
    }

    // ----------------------------------------------------------- collections

    @Test
    @DisplayName("a list of codec values is a JSON array of encoded strings")
    void listOfCodecValues() {
        // Commons' FieldDescriptor.serializeCollection built a JsonArray of the
        // per-element serialised strings. A list of locations is therefore
        // ["world,0.00,...","world,1.00,..."], not a JSON array of objects.
        org.bukkit.World world = FakeServer.newWorld("world");
        FakeServer.worlds(world);

        Holder written = new Holder("id", List.of(
                new org.bukkit.Location(world, 0, 64, 0, 0f, 0f),
                new org.bukkit.Location(world, 1, 64, 1, 0f, 0f)));

        Object stored = net.exylia.lib.database.internal.EntityModel.of(Holder.class)
                .valuesByName(written).get("spots");

        assertEquals("[\"world,0.00,64.00,0.00,0.00,0.00\",\"world,1.00,64.00,1.00,0.00,0.00\"]",
                stored, "the wire format of every stored location list in the ecosystem");
    }

    @Test
    @DisplayName("a list Commons wrote reads back with every element")
    void listRoundTripFromCommons() {
        org.bukkit.World world = FakeServer.newWorld("world");
        FakeServer.worlds(world);

        Holder read = net.exylia.lib.database.internal.EntityModel.of(Holder.class)
                .read(column -> switch (column) {
                    case "id" -> "arena";
                    case "spots" -> "[\"world,10.00,64.00,10.00,0.00,0.00\","
                            + "\"world,20.00,64.00,20.00,0.00,0.00\"]";
                    default -> null;
                });

        assertEquals(2, read.spots().size());
        assertEquals(10.0, read.spots().get(0).getX());
        assertEquals(20.0, read.spots().get(1).getX());
    }

    @Test
    @DisplayName("a list of plain values is stored as Gson would, not as strings")
    void listOfPlainValues() {
        // Commons only built the array-of-strings form when the element type had
        // a registered serializer, and fell through to GSON.toJson(collection)
        // otherwise. So a list of strings is ["a","b"] — which happens to look
        // the same — and a list of numbers is [1,2], NOT ["1","2"]. Unifying
        // these would be tidier and would make every stored numeric list
        // unreadable.
        Names written = new Names("id", List.of("alpha", "beta"));

        assertEquals("[\"alpha\",\"beta\"]",
                net.exylia.lib.database.internal.EntityModel.of(Names.class)
                        .valuesByName(written).get("tags"));
    }

    @Test
    @DisplayName("an empty list is stored as an empty array, not as absent")
    void emptyList() {
        // The second deliberate difference. Commons wrote null for an empty
        // collection, which makes "the player has no kits" and "this row was
        // written before the column existed" the same stored value. Writing []
        // keeps them apart, and a reader cannot tell the difference anyway:
        // both come back as an empty list.
        assertEquals("[]", net.exylia.lib.database.internal.EntityModel.of(Names.class)
                .valuesByName(new Names("id", List.of())).get("tags"));
    }

    @Test
    @DisplayName("a null a Commons row stored for an empty list still reads")
    void nullListFromCommonsStillReads() {
        // Which is what makes the difference above safe: every row Commons
        // wrote is still readable.
        Names read = net.exylia.lib.database.internal.EntityModel.of(Names.class)
                .read(column -> column.equals("id") ? "x" : null);

        assertTrue(read.tags().isEmpty());
    }

    @Test
    @DisplayName("an absent list reads back empty rather than null")
    void absentListReadsEmpty() {
        // The one place a read differs from Commons, deliberately. Commons
        // handed back null, so every list consumer in the ecosystem is either
        // null-checking or a latent NPE. Reading absence as empty still reads
        // every row Commons ever wrote.
        Names read = net.exylia.lib.database.internal.EntityModel.of(Names.class)
                .read(column -> column.equals("id") ? "x" : null);

        assertNotNull(read.tags());
        assertTrue(read.tags().isEmpty());
    }

    @Table("compat_holder")
    private record Holder(@Id String id, @Column List<org.bukkit.Location> spots) {
    }

    @Table("compat_names")
    private record Names(@Id String id, @Column List<String> tags) {
    }
}
