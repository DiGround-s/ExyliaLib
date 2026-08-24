# Panel Field Descriptors Specification

## Purpose

The binding between an element type and the generic list panel. The library ships exactly two: `RewardEntry` and `Effects.ParsedEffect`. Everything else is a consumer-owned descriptor.

## ADDED Requirements

### Requirement: Descriptor contract

A `FieldDescriptor<T>` MUST declare, for one element type: how a row is drawn (label and icon), how an entry is identified, how a new entry is created, how an entry is edited, and how the resulting list is persisted. A descriptor MUST NOT contain pagination, search, clipboard, undo, confirmation, save, or cancel logic — those belong to the list panel. A descriptor MUST be constructible without a running server so it can be tested directly.

#### Scenario: Descriptor carries no panel machinery

- GIVEN the two built-in descriptors
- WHEN their public surface is inspected
- THEN neither exposes pagination, search, clipboard, or undo behaviour
- AND each is usable by the single generic list panel unchanged

#### Scenario: Identity is defined by the descriptor, not the list

- GIVEN a descriptor that defines identity
- WHEN two entries with equal payloads and different identities are held
- THEN the list panel treats them as distinct entries

### Requirement: `RewardEntry` descriptor

The library MUST ship a descriptor for `RewardEntry`. It MUST reuse the existing API: `toBuilder()` for edits (preserving the id), `copy()` for duplication (new id), `displayName()` for the row label, and `resolvedIcon()` for the row icon. It MUST NOT reimplement reward rendering, chance/weight semantics, or the reward codec. Persistence MUST go through `RewardCodec`'s existing stored form, unchanged.

#### Scenario: Editing preserves the id

- GIVEN a `RewardEntry` in a list panel
- WHEN its amount is edited and the list is saved
- THEN the saved entry's id equals the original id
- AND all other fields are unchanged

#### Scenario: Duplicating assigns a new id

- GIVEN a `RewardEntry` is copied and pasted
- WHEN the list is saved
- THEN two entries exist whose payloads match and whose ids differ

#### Scenario: Stored form is byte-compatible

- GIVEN a list of rewards written by the existing codec
- WHEN it is opened in the list panel and saved with no edit
- THEN the stored string is unchanged, so a plugin not yet migrated still reads it

### Requirement: `Effects.ParsedEffect` descriptor

The library MUST ship a descriptor for `Effects.ParsedEffect` (`name`, `amplifier`, `duration`). Entries MUST be addressed by their carried value, never by list index — this descriptor is the one that reproduces the Commons potion-editor bug if that rule is broken. Persistence MUST produce the existing `NAME:amplifier:duration|…` string form, so an existing config file round-trips unchanged.

#### Scenario: String form round-trips

- GIVEN the string `SPEED:1:300|JUMP_BOOST:2:120`
- WHEN it is opened in the list panel and saved with no edit
- THEN the produced string parses to the same two effects in the same order

#### Scenario: The Commons potion bug cannot recur

- GIVEN a list of parsed effects spanning more than one page
- WHEN an entry on a later page is deleted
- THEN the removed effect is the one the clicked row carried
- AND the remaining effects and their order are otherwise unchanged

#### Scenario: Unknown effect name is not destroyed

- GIVEN a stored effect whose name the server does not resolve
- WHEN the list is opened and saved without touching that entry
- THEN that entry is still present in the saved string with its original values

### Requirement: No `NamedCommand` domain type

The library MUST NOT introduce a `NamedCommand` type, module, or descriptor. Named commands MUST be delivered as a documented example in `docs/panels.md` showing the generic list panel over a consumer-owned record of three strings. Promoting it later MUST remain a separate, additive change.

#### Scenario: No NamedCommand type ships

- GIVEN the library's public and internal source
- WHEN it is scanned for a `NamedCommand` type
- THEN none exists

#### Scenario: The example is a consumer-owned record

- GIVEN the documented named-command example
- WHEN it is followed
- THEN a consumer record plus one `FieldDescriptor` is sufficient, with no library change

### Requirement: Descriptors are the only extension point

Supporting a further element type MUST require only a new `FieldDescriptor` supplied by the consumer. The library MUST NOT gain a per-type panel, session, registry, or clipboard class for it, and the built-in descriptors MUST NOT be privileged over consumer-supplied ones.

#### Scenario: A consumer descriptor behaves identically

- GIVEN a consumer record and its descriptor
- WHEN the list panel is opened over it
- THEN pagination, search, copy, paste, delete, undo, save, and cancel behave exactly as for the built-in descriptors

## Threading, Nullability, Folia

- Descriptor callbacks for identity, creation, and copying MUST be pure and callable from any thread.
- Row label and icon resolution MAY require a viewer and MUST then run on the viewer thread (entity thread on Folia).
- Persistence MUST run off the viewer thread via `runAsync`.
- No descriptor MAY return `null` for an identity or a label; an unresolvable icon MUST fall back rather than throw.
- Behaviour MUST be identical on Spigot, Paper, Purpur, and Folia.

## Test Seams

Codec round-trips for both descriptors are plain JUnit — no server — because `RewardCodec` and the effect string form are already text. Row rendering uses `FakeServer`/`FakePlayer`. `SnapshotCodec`'s `ItemIo`-style seam pattern applies where an `ItemStack` would otherwise be needed for `resolvedIcon()`. **New seam required**: a test-only `FieldDescriptor` over a trivial record, so the "descriptors are the only extension point" scenarios do not depend on either built-in.
