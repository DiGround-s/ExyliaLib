# Hooks

Turning other plugins' mechanics off for a player while they play something an
Exylia plugin owns. Each hook works without a compile-time dependency: a server
that does not run the hooked plugin pays nothing, and every call is a no-op.

## Skills

*Since 1.409.0.* Entry point: `net.exylia.lib.hook.skill.SkillHooks`.

An event, a duel or an arena is only fair if a level-80 skill player hits like
everybody else. Suspend the player on the way in and resume them on the way out:

```java
SkillHooks.suspend(plugin, player);   // false when this plugin already had them suspended
SkillHooks.resume(plugin, player);
SkillHooks.isSuspended(player.getUniqueId());
SkillHooks.plugins();                 // ["AuraSkills"]: what a suspension holds back on this server
```

While a player is suspended:

| Plugin | Held back |
| --- | --- |
| AuraSkills | skill experience (`XpGainEvent`), mana abilities (`ManaAbilityActivateEvent`), and the stat and ability modifiers on their hits and on hits they take (`DamageEvent`: attack modifiers when they attack, defense modifiers when they are hit) |
| mcMMO | experience (`McMMOPlayerXpGainEvent`), super abilities (`McMMOPlayerAbilityActivateEvent`), and the passives mcMMO announces through `SubSkillEvent` (dodge, roll, critical strikes, disarm…) |

### Behavior

- **Nothing stored is touched.** The skill plugins' own events are cancelled or
  trimmed as they fire, at `HIGHEST`. Levels, stats and experience stay as they
  were, so a crash mid-event leaves nothing to restore.
- **Not held back:** AuraSkills stats that are not damage modifiers (max health
  from Health, regeneration, luck). A server that needs those gone too keeps the
  event world in AuraSkills' `disabled_worlds`.
- **Per owner.** A player suspended by two plugins stays suspended until both
  resume them. Resuming a player nobody suspended does nothing.
- **Memory only.** The player's quit and the owner plugin's disable drop the
  suspension; there is nothing to recover on join.
- **Cost.** While nobody is suspended a skill event costs an empty-map check.
- **A changed API is logged once** at startup (`Skill hooks: <plugin> is not
  held back…`) and that event is left out; the rest keep working.
- **Threads:** any. The events are answered on whatever thread fires them.
- **Reload:** not applicable, the module keeps nothing derived from the palette.

A plugin that keeps players through [Stays](stays.md) gets this for every
stay with `Stays.of(plugin).holdSkillsBack()` (since 1.411.0), without calling
`suspend` and `resume` itself.

Code: `hook/skill/SkillHooks` (API), `hook/skill/internal/SkillHookRuntime`
(suspensions and the listened events). Adding a skill plugin is one line in
`SkillHookRuntime.SOURCES`.

## Pets

*Since 1.604.0.* Entry point: `net.exylia.lib.hook.pet.PetHooks`.

A vanished staff member whose pet keeps following them is not vanished. Put
the pets away while the player must not be seen, and call them back after:

```java
PetHooks.hide(plugin, player);    // false when this plugin already hid them
PetHooks.show(plugin, player);
PetHooks.isHidden(player.getUniqueId());
PetHooks.plugins();               // ["MCPets", "MyPet"]: what a hold puts away on this server
```

| Plugin | Put away | Called back |
| --- | --- | --- |
| MCPets | every active pet, despawned as a teleport (still the pet the player has out) | `spawn` at the player |
| MyPet | the active pet, removed as one the player wants back | `createEntity` |

### Behavior

- **Held back while hidden.** A pet the player or the pet plugin calls while
  they are hidden (a menu, a respawn timer, the respawn on join) is cancelled
  and comes back with the rest on `show`.
- **Per owner.** Pets come back once every plugin hiding the player showed them.
- **Memory only.** On quit the hold is dropped and the pet plugin saves what
  the player had out as usual, so a relog brings the pets back unless the
  player is hidden again. The owner's disable drops its holds without
  calling pets back.
- **A changed API is logged once** at startup (`Pet hooks: <plugin> pets are
  not put away…`) and that plugin is left out.
- **Threads:** the player's thread; MCPets and MyPet are not Folia plugins.

Code: `hook/pet/PetHooks` (API), `hook/pet/internal/PetHookRuntime` (holds and
one `Source` per pet plugin). Adding a pet plugin is one entry in `SOURCES`.

## Permission groups

*Since 1.469.0.* Entry point: `net.exylia.lib.hook.group.PermissionGroups`.

Rules written by rank ("staff never play events", "VIP arenas") need to know
which groups a player is in, and a menu that offers those ranks needs the list:

```java
PermissionGroups.isIn(player, "staff");          // inherited groups count
PermissionGroups.isInAny(player, blockedRanks);
PermissionGroups.of(player);                     // ["default", "vip"]
PermissionGroups.all();                          // every group, for a picker
PermissionGroups.canList();                      // false: offer typing a name instead
```

### Behavior

- **Vault first.** With Vault and a permission plugin that has groups
  (LuckPerms, PermissionsEx, GroupManager...), every answer comes from Vault's
  permission service, reached by reflection: no compile dependency, and a
  server without Vault loads the class fine.
- **Without Vault** membership falls back to the `group.<name>` node LuckPerms
  and most permission plugins grant, counted only when it is explicitly set.
  An operator, who holds every unset node, is never taken as a member of every
  group. `all()` is empty there, so a picker falls back to a typed name.
- Names are lower-cased; comparisons are case-insensitive.
- **Threads:** whatever the permission plugin allows (LuckPerms: any).
- **Reload:** nothing is cached but the reflected method handles; the service
  is asked for on every call, so a permission plugin swapped at runtime is seen.

Code: `hook/group/PermissionGroups`.
