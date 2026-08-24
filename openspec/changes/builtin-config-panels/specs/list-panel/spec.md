# List Panel Specification

## Purpose

One generic paginated list editor — paginate, search, copy, paste, delete, undo, save, cancel — parameterised by a `FieldDescriptor<T>`. Exactly one implementation for every element type.

## Requirements

### Requirement: Entries are addressed by their carried value, never by index

Every drawn row MUST carry its element through `UiKeys.ENTRY`, and every operation — edit, copy, delete, move — MUST resolve its target from that carried value. No operation MAY use slot number, page number, or list index as entry identity. This is the verified Commons potion-editor bug and MUST be impossible to reintroduce.

#### Scenario: Delete under pagination hits the right entry

- GIVEN 60 entries across three pages
- WHEN the viewer deletes the second row shown on page 3
- THEN the deleted element is the one that row carried
- AND elements at list indices 1 and 2 are untouched

#### Scenario: Delete after a search filter hits the right entry

- GIVEN a search showing a non-contiguous subset
- WHEN the first shown row is deleted
- THEN the removed element is the one that row carried, not index 0 of the unfiltered list

#### Scenario: Resolution is independent of list order

- GIVEN a drawn, filtered, paginated view
- WHEN the backing list is reordered between draw and click
- THEN the click still resolves the element carried by `UiKeys.ENTRY`

### Requirement: Pagination and search

The panel MUST paginate the working copy and MUST take page numbers from the section, not the caller. Search MUST reuse the existing `SearchInput` and MUST NOT be reimplemented. A search MUST filter the view only, never the working copy; clearing it MUST restore the full view intact.

#### Scenario: Page navigation redraws only the list

- GIVEN a three-page list
- WHEN the next-page control is used
- THEN list slots are redrawn and unrelated slots are not re-sent

#### Scenario: Search filters the view only

- GIVEN 20 entries and a search matching 3
- WHEN the search is applied and then cleared
- THEN 3 rows show while filtered and 20 after clearing
- AND the working copy holds 20 entries throughout

#### Scenario: Empty result explains itself

- GIVEN a search matching no entry
- WHEN the view is drawn
- THEN a pagination filler stating why the list is empty is shown, distinct from the background filler

### Requirement: Copy, paste, delete, undo

Copy MUST place the clicked entry into a session-scoped clipboard. Paste MUST insert a distinct copy; where the descriptor defines an identity, the pasted entry MUST receive a new one. Delete MUST be confirmed through `ConfirmInput.dangerous()` and MUST be undoable. Undo MUST restore the state before the last committed operation, bounded per `panel-engine`. The clipboard MUST die with the session and MUST NOT be a static map.

#### Scenario: Delete is undoable

- GIVEN 5 entries
- WHEN one is deleted after confirmation and undo runs
- THEN 5 entries remain and the restored entry equals the deleted one

#### Scenario: Paste produces a distinct entry

- GIVEN an entry with an identity is copied
- WHEN it is pasted
- THEN two entries exist with matching payloads and different identities

#### Scenario: Clipboard dies with the session

- GIVEN a viewer with a copied entry
- WHEN the panel closes, or the viewer quits, or the owning plugin is disabled
- THEN no clipboard entry remains held for that viewer

#### Scenario: Destructive delete asks first

- GIVEN a delete is triggered
- WHEN the confirmation is denied
- THEN the working copy is unchanged

### Requirement: Diff before save, all-or-nothing cancel

Save MUST present a diff of added, removed, and changed entries before persisting, and MUST persist only through the descriptor's write path. Cancel MUST discard the entire working copy — deletions, pastes, and edits alike — and MUST NOT partially persist any of them.

#### Scenario: Diff names what changed

- GIVEN one added, one removed, and one edited entry
- WHEN save runs
- THEN the diff reports exactly one addition, one removal, and one change

#### Scenario: Cancel discards everything

- GIVEN a deletion and two edits
- WHEN cancel runs
- THEN the persisted list equals the list as it was when the panel opened

#### Scenario: Save with no change writes nothing

- GIVEN an unmodified list
- WHEN save runs
- THEN no write is performed

### Requirement: One implementation, parameterised

The list panel MUST be a single generic implementation parameterised by `FieldDescriptor<T>`. Supporting a new element type MUST require only a new descriptor and MUST NOT require a new panel, menu, session, registry, or clipboard class.

#### Scenario: A new element type needs only a descriptor

- GIVEN a consumer-owned record with no library support
- WHEN a `FieldDescriptor` for it is supplied
- THEN paginate, search, copy, delete, undo, and save all work with no additional class

## Threading, Nullability, Folia

- Working-copy mutation, filtering, and diff: any thread, no Bukkit API.
- Draw, click handling, and input requests: viewer thread (entity thread on Folia) via `net.exylia.lib.task`; persistence via `runAsync`, returning before touching the game.
- The clipboard MAY be empty; paste with an empty clipboard MUST be a no-op, not an error.
- Behaviour MUST be identical on Spigot, Paper, Purpur, and Folia.

## Test Seams

`FakeServer`/`FakePlayer` for open, click, quit, disable; `DebugCapture` for reports. **New seams required** (for `sdd-tasks`): a test descriptor over a simple record so list behaviour is proven independently of the built-ins; a scripted input seam supplying deterministic `SearchInput` and `ConfirmInput.dangerous()` answers without a live transport; the renderer/draw sink from `panel-engine` so a test can read which element each row carried.
