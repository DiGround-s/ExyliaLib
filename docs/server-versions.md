# Server versions

Exylia plugins run on Paper 1.21.1 and newer (Folia 1.21.4 and newer: Folia
has no 1.21.1 build) while compiling against paper-api 1.21.4. A newer
feature is detected when the server starts and switched off where it is
missing, so a 1.21.4+ server runs exactly the code it always did.

Code lives in `src/main/java/net/exylia/lib/platform/`; the check lives in
`tools/compat/`.

## The rule

Never name server API newer than 1.21.1 in a class that loads everywhere:
not in a field, a signature, a method body or a lambda. Put it in its own
class, mark that class `@RequiresServer("<version>")`, and only reach it after
a `Features` probe said yes.

```java
@RequiresServer("1.21.4")
final class ModernModels {
    static void apply(ItemMeta meta, NamespacedKey key) {
        meta.setItemModel(key);
    }
}

if (Features.itemModel()) {
    ModernModels.apply(meta, key);
}
```

A plugin that genuinely needs a newer server says so in `plugin.yml`
(`api-version: '1.21.9'`); the server then refuses to load it on anything
older, with its own message.

## `Features`

Each probe asks whether the class or method behind a feature exists, never
which version the server reports, and is computed once into a
`static final` field: asking costs nothing.

| Probe | Feature | Arrived |
| --- | --- | --- |
| `dataComponents()` | Paper's data component API (`ItemStack#setData`) | 1.21.4 |
| `itemModel()` | `item_model` | 1.21.4 |
| `tooltipStyle()` | `tooltip_style` | 1.21.2 |
| `textShadow()` | Adventure `ShadowColor` | 1.21.4 |
| `playerInput()` | `PlayerInputEvent` | 1.21.3 |
| `menuBuilders()` | `MenuType` view builders | 1.21.4 |
| `equipmentEvent()` | `EntityEquipmentChangedEvent` | 1.21.4 |

## `Attributes`

1.21.2 dropped the `generic.`/`player.`/`zombie.`/`horse.` prefixes from
attribute keys and 1.21.3 renamed the Bukkit fields to match
(`GENERIC_MAX_HEALTH` became `MAX_HEALTH`). Code compiled against the new
fields dies on 1.21.1 with `NoSuchFieldError`; the old ones do not compile.

```java
AttributeInstance health = player.getAttribute(Attributes.MAX_HEALTH);
AttributeInstance scale = Attributes.of(player, Attributes.SCALE);   // null-tolerant
Attribute fromConfig = Attributes.byKey("generic.movement_speed");   // any spelling
NamespacedKey key = Attributes.key(attribute);                       // never attribute.getKey()
```

- Every constant exists on every supported server, so none is `null` there.
- `byKey` accepts `max_health`, `generic.max_health`, `minecraft:…` and the
  Bukkit field names (`GENERIC_MAX_HEALTH`, `MAX_HEALTH`), so a config written
  for either version reads on both. Item modifiers and mob attributes in
  ExyliaLib configs go through it.

## `Constants`

1.21.3 turned `Sound`, `Attribute`, `Biome` and others from enums into
interfaces. `Sound.valueOf(name)` and `sound.getKey()` compiled against 1.21.4
are interface calls, and on 1.21.1 they throw
`IncompatibleClassChangeError`.

```java
Sound sound = Constants.named(Sound.class, "ENTITY_PLAYER_LEVELUP");
NamespacedKey key = sound == null ? null : Registry.SOUNDS.getKey(sound);
```

`named` calls the type's own `valueOf` by reflection first (Paper's maps
renamed names) and falls back to reading its static fields. For a key, ask
the registry (`Registry.SOUNDS.getKey(sound)`, `Registry.BIOME.getKey(biome)`).

## What a 1.21.1 server does without

| Feature | On 1.21.1 |
| --- | --- |
| `item_model`, `tooltip_style` in item configs | Ignored, with one console line per server; `custom-model-data` still works |
| `force-consumable` items | Refused with a console line (needs the data component API) |
| Text shadow (`text-shadow` setting, `<shadow>`) | Not drawn; text keeps its colours |
| Anvil search box | Opened through the older `openAnvil` door; same behaviour |
| Kit comparison (`Loadout`) and replay item sampling | Through `ItemMeta` instead of components; same result |

## `check-compat.sh`

javac cannot see any of this: everything compiles against 1.21.4. The check
reads the compiled bytecode instead.

```bash
./gradlew classes
tools/compat/check-compat.sh build/classes/java/main          # or a jar
tools/compat/check-compat.sh --minimum 1.21.1 <dir-or-jar>...
```

It downloads paper-api for the minimum version with everything it brings
(Adventure, gson…) once, then reports, one line each:

- a class, field or method the old API does not have;
- a call that treats a type as an interface when it is a class there, or the
  reverse (the `Sound`/`Attribute` break above);
- a field or method of the checked class whose signature names a missing type
  (an event listener for an event the server lacks).

Classes marked `@RequiresServer`, and their nested classes, are skipped. Exit
code 1 when anything is reported.
