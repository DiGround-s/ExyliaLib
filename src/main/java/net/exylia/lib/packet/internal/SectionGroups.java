package net.exylia.lib.packet.internal;

import org.bukkit.Location;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups block positions by the 16x16x16 chunk section they fall in.
 *
 * <p>A multi-block-change packet is one section, so this decides how many
 * packets a set of fake blocks costs. Pure and PacketEvents-free so the
 * grouping is testable without a server.
 */
public final class SectionGroups {

    private SectionGroups() {
    }

    /** A chunk section: chunk x and z, and the section's index along y. */
    public record Section(int x, int y, int z) {

        /** The section holding a block position. Floors negatives correctly. */
        public static Section of(int blockX, int blockY, int blockZ) {
            return new Section(blockX >> 4, blockY >> 4, blockZ >> 4);
        }
    }

    /**
     * Groups positions by section, keeping first-seen order.
     *
     * @param positions block positions, all in one world
     * @return positions per section
     */
    public static Map<Section, List<Location>> group(Collection<Location> positions) {
        Map<Section, List<Location>> groups = new LinkedHashMap<>();
        for (Location at : positions) {
            Section section = Section.of(at.getBlockX(), at.getBlockY(), at.getBlockZ());
            groups.computeIfAbsent(section, ignored -> new java.util.ArrayList<>()).add(at);
        }
        return groups;
    }
}
