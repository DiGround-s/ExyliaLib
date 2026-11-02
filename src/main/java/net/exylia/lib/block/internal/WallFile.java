package net.exylia.lib.block.internal;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The file that remembers a standing wall, so the next start can take it down.
 *
 * <p>One file per wall: a header of three lines (id, world, material), then one
 * line per block the wall replaced, {@code x y z blockData}. The text form is
 * pure, so it is tested without a server.
 *
 * @since 1.458.0
 */
public final class WallFile {

    /** One block a wall replaced, and what was there before. */
    public record Placed(int x, int y, int z, @NotNull String previous) {
    }

    /** What a file holds. */
    public record Contents(@NotNull String id, @NotNull String world, @NotNull String material,
                           @NotNull List<Placed> blocks) {
    }

    static final String EXTENSION = ".wall";

    private WallFile() {
        throw new AssertionError("No instances.");
    }

    /** The file a wall id is kept in: the id in URL-safe Base64, so any id is a valid file name. */
    public static @NotNull File of(@NotNull File folder, @NotNull String id) {
        return new File(folder, Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.getBytes(StandardCharsets.UTF_8)) + EXTENSION);
    }

    /** Every wall file in a folder; empty when there is none. */
    public static @NotNull List<File> list(@NotNull File folder) {
        File[] files = folder.listFiles((dir, name) -> name.endsWith(EXTENSION));
        return files == null ? List.of() : List.of(files);
    }

    public static @NotNull String encode(@NotNull Contents contents) {
        StringBuilder out = new StringBuilder();
        out.append(contents.id().replace('\n', ' ')).append('\n')
                .append(contents.world()).append('\n')
                .append(contents.material());
        for (Placed placed : contents.blocks()) {
            out.append('\n').append(placed.x()).append(' ').append(placed.y()).append(' ').append(placed.z())
                    .append(' ').append(placed.previous());
        }
        return out.toString();
    }

    /** Reads a file's text back; {@code null} when the header is missing. A damaged block line is skipped. */
    public static @Nullable Contents decode(@NotNull String text) {
        String[] lines = text.split("\n");
        if (lines.length < 3) return null;
        List<Placed> blocks = new ArrayList<>(Math.max(0, lines.length - 3));
        for (int index = 3; index < lines.length; index++) {
            String[] parts = lines[index].split(" ", 4);
            if (parts.length < 4) continue;
            try {
                blocks.add(new Placed(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), parts[3]));
            } catch (NumberFormatException damaged) {
                // One block left behind, not a wall that never comes down.
            }
        }
        return new Contents(lines[0], lines[1], lines[2], blocks);
    }

    /** Writes a file whole or not at all: a crash mid-write leaves the old one, never half of it. */
    public static void write(@NotNull File file, @NotNull Contents contents) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.writeString(temporary.toPath(), encode(contents), StandardCharsets.UTF_8);
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    public static @Nullable Contents read(@NotNull File file) throws IOException {
        return decode(Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }
}
