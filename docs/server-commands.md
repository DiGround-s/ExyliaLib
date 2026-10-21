# Server commands

Commands run for a player on one server of the network, whenever they are
there: a kit bought in the lobby is given when they reach survival, a reward
for the event server waits until they join it.

```java
ServerCommands commands = ServerCommands.of(this);   // once, in onEnable
commands.run(buyer, "survival", List.of("console: kit give %player_name% starter"), Map.of());
commands.run(buyer, "", List.of("console: lp user %player_name% parent add vip"), Map.of());
```

- **On that server already** (or a blank server, and here): run now, on the
  player's thread.
- **Anywhere else, or offline**: written to the plugin's database
  (`exylia_server_commands`) and announced on the plugin's `server-commands`
  channel. The server the player is on runs them at once if it is the right one;
  otherwise they run on the player's next join there, two seconds in.
- A batch is taken by deleting its row: it runs once, on one server. A player
  who leaves between the take and the run gets the batch written back.
- The server is the `server-id` `Redis.serverId` answers. Without Redis nothing
  is announced, so a batch for another server waits for a join there.
- Lines are [command lines](../src/main/java/net/exylia/lib/command/CommandLine.java)
  (`console:`, `player:`), run through `Commands.of(plugin)` with the player and
  the values given.

`server()` is this server's `server-id`, in lower case.

## Where the code is

`net.exylia.lib.command.ServerCommands`, its row `command.internal.ServerCommandRow`.
