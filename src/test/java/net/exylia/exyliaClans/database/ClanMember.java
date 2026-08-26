package net.exylia.exyliaClans.database;

import java.util.UUID;

/** A stand-in for ExyliaClans' own member row. See {@link Clan}. */
public class ClanMember {

    private final String uuid;
    private final String clanId;

    public ClanMember(UUID uuid, String clanId) {
        this.uuid = uuid.toString();
        this.clanId = clanId;
    }

    public String getId() {
        return uuid;
    }

    public UUID getUUID() {
        return UUID.fromString(uuid);
    }

    public String getClanId() {
        return clanId;
    }
}
