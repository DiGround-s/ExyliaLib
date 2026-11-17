# Parties

Player parties, one set for the whole server: a party made for a dungeon is the
same party a duels or events plugin sees. Invitations with clickable buttons, a
leader, kicks, party chat, a grace period for members who drop, and ready checks
before something starts. Since 1.621.0.

Entry point: `net.exylia.lib.party.Parties`.

## Using

```java
@Override
public void onEnable() {
    Parties.use(this);                       // puts /party on the server (once, whoever asks first)
    Parties.watch(this, new PartyWatcher() {
        @Override public void left(Party party, UUID player, PartyLeave reason) { runs.drop(player); }
        @Override public void ended(Party party) { runs.cancel(party.id()); }
    });
}

Optional<Party> party = Parties.of(player);   // empty: playing alone
Parties.together(a, b);                       // same party?
Parties.readyCheck(leader, "Crypt of Ash", Duration.ofSeconds(20))
        .thenAccept(result -> { if (result == ReadyResult.READY) runs.start(leader); });
```

`Party` is a snapshot: `id`, `leader`, `members` (leader first), `offline`,
`maxSize`, plus `online()`, `isMember`, `isLeader`. Read it again after a change.

Everything is safe from any thread. Watchers are called outside the book's lock;
a ready check's future completes on the global thread a tick later.

## The command

`/party` (names in ExyliaLib's `config.yml`, `parties.command`) exists only once a
plugin calls `Parties.use`, so a server with its own party plugin keeps its
command. A name another plugin already registered is skipped; with every name
taken the library warns and registers nothing.

| Subcommand | Does |
| --- | --- |
| *(none)* | members, who is online, the commands |
| `invite <player>` | invites; starts a party when you have none |
| `accept [leader]` / `deny [leader]` | answers the newest invitation, or that leader's |
| `leave`, `disband` | leave; the leader ends the party |
| `kick <member>`, `leader <member>` | leader only |
| `chat [message]` | one line to the party, or toggles party chat |
| `ready`, `notready` | answers a ready check (the buttons run these) |

Accepting another invitation leaves your current party first (`PartyLeave.SWITCHED`).

## Settings

```yaml
parties:
  command: { enabled: true, name: party, aliases: [p] }
  max-size: 5
  invite-expiry: 60s
  rejoin-grace: 5m      # a member who quits keeps their place this long
```

The lines live in ExyliaLib's `messages.yml` under `parties:` (`PartyMessages`).

## Rules

- One party per player. A party ends when one member is left and no invitation waits.
- A leader who quits hands the lead to the first online member.
- Ready checks: the leader counts as ready; anyone offline ends it as `OFFLINE`;
  a join, leave or quit ends it as `CHANGED`; a player alone gets `READY` at once.
- One server only: a network does not share parties yet.
