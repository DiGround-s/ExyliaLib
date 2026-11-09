package net.exylia.lib.packet.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Reading the interact packet by hand hears no click on a 26.1+ server: every click goes through EntityClicks. */
class EntityClicksSourceTest {

    @Test
    void onlyEntityClicksReadsTheInteractPacket() throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            List<String> readers = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.endsWith("EntityClicks.java"))
                    .filter(f -> {
                        try {
                            return Files.readString(f).contains("new WrapperPlayClientInteractEntity(event)");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).map(f -> root.relativize(f).toString()).toList();
            assertEquals(List.of(), readers);
        }
    }
}
