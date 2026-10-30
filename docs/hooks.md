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

Code: `hook/skill/SkillHooks` (API), `hook/skill/internal/SkillHookRuntime`
(suspensions and the listened events). Adding a skill plugin is one line in
`SkillHookRuntime.SOURCES`.
