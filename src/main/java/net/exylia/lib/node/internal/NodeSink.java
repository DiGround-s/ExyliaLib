package net.exylia.lib.node.internal;

/** Where node packets go: PacketEvents on a server, a recorder in tests. */
interface NodeSink {

    /** Sends both entities with everything the node looks like now. */
    void spawn(NodeImpl node);

    void despawn(NodeImpl node);

    void look(NodeImpl node);

    void glow(NodeImpl node);

    void scale(NodeImpl node, float size, int ticks);
}
