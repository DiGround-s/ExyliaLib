package net.exylia.lib.clientmob.internal;

/** Where mob packets go: PacketEvents on a server, a recorder in tests. */
interface MobSink {

    /** Sends the entity with everything the mob looks like now. */
    void spawn(MobImpl mob);

    void despawn(MobImpl mob);

    /** A small step: the client interpolates it. */
    void move(MobImpl mob, double dx, double dy, double dz);

    /** Puts it exactly at its location, facing its yaw and pitch. */
    void teleport(MobImpl mob);

    /** Turns body and head without moving. */
    void rotate(MobImpl mob);

    void swing(MobImpl mob);

    void status(MobImpl mob, byte status);

    /** Resends name, pose and age. */
    void state(MobImpl mob);

    void glow(MobImpl mob);
}
