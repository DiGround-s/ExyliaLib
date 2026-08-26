package net.exylia.exyliaClans.database;

import java.util.UUID;

/**
 * A stand-in for ExyliaClans' own clan row.
 *
 * <p>Copied signature for signature from
 * {@code ExyliaClans/src/main/java/net/exylia/exyliaClans/database/Clan.java},
 * including the getters Lombok generates there. ExyliaLib never compiles
 * against ExyliaClans, so the only way to prove the provider still reads it is
 * to stand up the shape it reaches for and read it.
 */
public class Clan {

    private final String id;
    private final String displayName;
    private final String leaderId;
    private final double balance;

    public Clan(String id, String displayName, UUID leaderId, double balance) {
        this.id = id;
        this.displayName = displayName;
        this.leaderId = leaderId.toString();
        this.balance = balance;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public UUID getLeaderUUID() {
        return UUID.fromString(leaderId);
    }

    public double getBalance() {
        return balance;
    }
}
