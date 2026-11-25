package net.exylia.lib.node.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class NodeBoxTest {

    @Test
    void fitsWhatTheViewerSees() {
        assertArrayEquals(new float[]{1f, 1f}, NodeBox.of(NodeBox.Shape.FULL, 0, 0, 1f));
        assertArrayEquals(new float[]{1f, 0.0625f}, NodeBox.of(NodeBox.Shape.FLAT, 0, 0, 1f));
        // A ripe wheat (7 of 7) is a full-height crop; a seedling keeps a box worth clicking.
        assertArrayEquals(new float[]{0.875f, 1f}, NodeBox.of(NodeBox.Shape.CROP, 7, 7, 1f));
        assertArrayEquals(new float[]{0.875f, 0.25f}, NodeBox.of(NodeBox.Shape.CROP, 0, 7, 1f));
        assertArrayEquals(new float[]{0.875f, 0.5f}, NodeBox.of(NodeBox.Shape.CROP, 1, 3, 1f));
        assertArrayEquals(new float[]{0.75f, 0.8f}, NodeBox.of(NodeBox.Shape.PLANT, 0, 0, 1f));
        // It follows the node's scale, and a node scaled away has no box left to click.
        assertArrayEquals(new float[]{1.5f, 1.5f}, NodeBox.of(NodeBox.Shape.FULL, 0, 0, 1.5f));
        assertArrayEquals(new float[]{0f, 0f}, NodeBox.of(NodeBox.Shape.ITEM, 0, 0, 0f));
    }
}
