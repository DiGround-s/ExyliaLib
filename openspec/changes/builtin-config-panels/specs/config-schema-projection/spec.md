# Config Schema Projection Specification

## Purpose

A public, read-only description of a config record's schema — YAML key, Java component name, declared type, generic type, `@Comment` lines, nested node — so a UI can be generated from a `ConfigFile<T>` without any consumer touching `net.exylia.lib.config.internal`.

## ADDED Requirements

### Requirement: Public projection surface

The library MUST expose a public `Schema` describing a record type and a public `Schema.Field` describing one component. `Schema` MUST expose the described record `Class<?>`, its section-level `@Comment` lines, and its fields in canonical-constructor order. `Schema.Field` MUST expose `name` (Java component name), `key` (YAML key), `type` (declared `Class<?>`), `generic` (`java.lang.reflect.Type`), `comments` (`@Comment` lines), and `nested` (the nested `Schema` when the component is itself a record, otherwise `null`). Every collection returned MUST be immutable; `nested` MUST be the only nullable accessor. A `ConfigFile<T>` MUST answer its own schema through one accessor; that accessor MUST NOT return `null`.

#### Scenario: Annotated record projects every component

- GIVEN a record with an `@Key`-renamed component, an `@Comment`-documented component, and a nested record component
- WHEN the schema is requested from its `ConfigFile`
- THEN fields are returned in canonical-constructor order
- AND the renamed component's `key` is the `@Key` value while its `name` is the Java component name
- AND the documented component's `comments` equal the declared `@Comment` lines in declaration order
- AND the nested component's `nested` is a non-null `Schema` for the nested record type

#### Scenario: Un-annotated component falls back to kebab-case

- GIVEN a record component named `poolSize` with no `@Key`
- WHEN the schema is projected
- THEN its `key` is `pool-size`
- AND its `comments` is an empty immutable list

#### Scenario: Generic element type is recoverable

- GIVEN a component declared `List<String>`
- WHEN the schema is projected
- THEN `type` is `List.class`
- AND `generic` carries the `String` type argument, so a caller can determine the element type without reflecting on the record again

### Requirement: Projection is a copy, never a live handle

The projection MUST be a value copy taken at call time. It MUST NOT expose, wrap, or hold a reference reachable by a caller to `SchemaNode`, `SchemaNode.SchemaComponent`, `SchemaCache`, or any other `config.internal` type, and MUST NOT expose the canonical `Constructor<?>`. Mutating a config file after projection MUST NOT alter a previously returned `Schema`. `SchemaNode` and `SchemaComponent` MUST remain package-private.

#### Scenario: No internal type appears in a public signature

- GIVEN the public types of `net.exylia.lib.config` and `net.exylia.lib.panel`
- WHEN every public constructor, method return type, and parameter type is reflected over
- THEN no type whose package ends in `.internal` appears
- AND no `java.lang.reflect.Constructor` is reachable from `Schema` or `Schema.Field`

#### Scenario: Projection survives a reload of its source

- GIVEN a `Schema` obtained from a `ConfigFile`
- WHEN the file is reloaded and its values change
- THEN the previously returned `Schema` is unchanged and still readable
- AND requesting the schema again returns an equal projection, because schema describes the type, not the values

#### Scenario: Returned collections reject mutation

- GIVEN a projected `Schema`
- WHEN a caller attempts to add to `fields()` or to a field's `comments()`
- THEN `UnsupportedOperationException` is thrown

### Requirement: Deliberate exclusions

The projection MUST NOT expose current values, defaults, the backing `FileConfiguration`, migration history, or `ConfigIssue` state. Reading a value MUST remain `ConfigFile.get()`; writing MUST remain `ConfigFile.update(UnaryOperator<T>)`. The projection MUST be additive: no existing public signature in `net.exylia.lib.config` changes.

#### Scenario: Schema carries no values

- GIVEN two `ConfigFile` instances of the same record type holding different values
- WHEN both schemas are projected
- THEN the two projections are equal

## Threading, Nullability, Folia

- Projection MUST be callable from any thread and MUST NOT touch the Bukkit API, the server, or the filesystem; it is therefore Folia-safe by construction and identical on Spigot, Paper, Purpur, and Folia.
- Projection MUST be pure and side-effect free; repeated calls MUST NOT re-run reflection on the hot path (the analysed node is already cached by `SchemaCache`).
- Every accessor is `@NotNull` except `Schema.Field.nested()`.
- This capability caches nothing derived from the palette and therefore MUST be documented as exempt from `invalidateAll()`; see `panel-engine` for the module-wide rule.

## Test Seams

All scenarios are verifiable with plain JUnit — no server. Record fixtures live in the test source set; `FakeServer` is not required. The public-signature sweep is a reflection test over the two packages' public types.
