# Formats

Numbers, money, percentages and dates written the way a player should read
them — and read back the way a player types them.

```java
Formats.money(1250);        // "$1,250.00"
Formats.compact(1_500);     // "1.5K"
Formats.percent(75);        // "75%"
Formats.relative(stamp);    // "3d ago"

Amounts.parse("10M");       // 10000000
```

Since 1.25.0.

---

## Why this is in the library

Formatting a number is the sort of thing every plugin writes for itself in two
lines, and those two lines are wrong in the same three ways every time.

The ecosystem has **154 places** building a `DecimalFormat` or calling
`String.format` on a number. None of them pin a locale, so on a host in Spain
the same configuration renders `1,5K` where another server renders `1.5K`, and
neither looks wrong from the inside. They also disagree with each other: two
plugins on the same server show the same balance as `$1,250.00` and `1250.0`.

There is a second cost. A `DecimalFormat` built per call is **241 ns** and
allocates the formatter, its symbols and an internal buffer. That is fine once
and wasteful four thousand times a second, which is what a twenty-player server
with a ten-line scoreboard does.

---

## The four classes, and which one you want

| Class | What it is | When |
| --- | --- | --- |
| `Numbers` | Fixed formatting. `compact(1500)` is `"1.5K"` on every server, forever. | A map key, a value written to a file, anything another program parses |
| `Formats` | The same operations read through `formats.yml` | **Anything a player reads** |
| `Amounts` | Reads what a player typed | Command arguments |
| `Dates` | Timestamps and relative time | Both |

The split is the point. A server owner editing `formats.yml` must be able to
change every currency symbol on the server without changing what is stored in
the database.

```java
Formats.apply(euroSettings);

Numbers.compact(1500);   // "1.5K" — unchanged, it is not presentation
Formats.compact(1500);   // "1,5 k" — follows the owner's file
```

---

## Numbers

Fixed, allocation-free beyond the string returned, safe from any thread.

| Method | Example |
| --- | --- |
| `compact(long)` / `compact(double)` | `1500` → `"1.5K"` |
| `grouped(long)` | `1234567` → `"1,234,567"` |
| `grouped(double, int)` | `(1234.5, 2)` → `"1,234.50"` |
| `decimals(double, int)` | `(3.14159, 2)` → `"3.14"` |
| `trimmed(double, int)` | `(2.50, 2)` → `"2.5"` |
| `percent(double)` | `75` → `"75%"` |
| `percentOfFraction(double)` | `0.75` → `"75%"` |
| `percentOf(double, double)` | `(3, 4)` → `"75%"` |
| `ordinal(long)` | `3` → `"3rd"`, `11` → `"11th"` |

### Compact notation

Uppercase `K`, `M`, `B`, `T`, `Q`, from exactly 1000, one decimal, dropped when
it is zero. **This is byte-for-byte what ExyliaCommons produced**, because every
menu, lore line and scoreboard in the ecosystem is already written against it.

`999` stays `999` rather than rounding up to `1K`: a balance a player does not
have is not a rounding preference.

### Percentages take the scale their name says

`percent(75)` is `"75%"`. `percentOfFraction(0.75)` is `"75%"`.

ExyliaCommons had `formatPercent(0.75)` render `"0.75%"` while
`formatRatio(3, 4)` rendered `"75%"` — one scaled its input, the other did not,
and a call site could not tell you which. The stats the ecosystem already stores
(`winRate = wins / total * 100`) are on the hundred scale, so `percent` is the
method for them.

### Rounding is half-up

Java's default is half-even, which renders `2.5` as `2` and `3.5` as `4`.
Correct for statistics, and it reads as a stuck counter to a player watching a
number tick.

### Locale is fixed, never the host's

The bug this class exists to prevent. Both `Numbers` and `Formats` produce the
same bytes on a server in Madrid, Frankfurt or Cairo.

---

## Formats and `formats.yml`

The same operations, read through the server owner's file. Generated on first
start at `plugins/ExyliaLib/formats.yml`, reloaded by `/exylialib reload`
alongside the palette.

```yaml
money:
  symbol: '$'
  symbol-position: before
  space-after-symbol: false
  decimals: 2
  compact: true
  compact-threshold: 1000000

compact:
  decimals: 1
  lowercase-suffixes: false
  threshold: 1000

percent:
  decimals: 1
  show-plus: false

date:
  style: date
```

Every key carries its own worked example as a comment, and those examples are
executed by `DocumentedFormatsTest` — a comment that lies is worse than no
comment, because it is believed.

### Money never goes through a double

`Formats.money(BigDecimal)` keeps the value exact end to end. This is not
theoretical: `1.005` as a binary `double` is slightly *below* 1.005, so a
double-based formatter renders `$1.00` where the player typed 1.005 and expects
`$1.01`.

The `double` and `long` overloads exist because economy APIs hand out a
`double` whether you wanted one or not. They convert through
`BigDecimal.valueOf`, which reads the shortest decimal that round-trips rather
than the binary noise underneath.

### The compact threshold

Money shortens only past `compact-threshold` (default 1,000,000). Below it the
amount is written out in full, so a price a player has to approve stays exact:
`$999,999.00`, never `$1M` for an amount that is not one million.

### A reload is atomic

Settings are published as one immutable object behind a volatile field. A render
sees every old value or every new one, never the new symbol beside the old
decimal count.

### Cost

Measured by `FormatsBenchmark`, in the repo:

| Call | ns |
| --- | --- |
| `Formats.compact(long)` | 29 |
| `Formats.percent(double)` | 30 |
| `Formats.money(long)` | 62 |
| `Formats.money(BigDecimal)` | 64 |
| a `DecimalFormat` built per call, as the ecosystem does today | 239 |
| `apply()` — twice in the life of a server | 62 |

Four thousand compact calls a second is 0.12 ms — two thousandths of one tick's
budget. Money costs more because it refuses to leave `BigDecimal`; that is the
trade being bought.

Everything derived from the settings is computed once in `apply`. The reload
path pays, the render path does not.

---

## Amounts — reading what a player typed

```java
/pay Steve 10M
```

```java
Amounts.parse("10M");      // Optional[10000000]
Amounts.parse("1.5k");     // Optional[1500]
Amounts.parse("2,500");    // Optional[2500]
Amounts.parse("2_500");    // Optional[2500]
Amounts.parseWhole("64");  // Optional[64]
```

Returns `Optional<BigDecimal>`, because a balance is the one number a `double`
must not hold.

### What is refused, and why

The interesting half is not the suffixes.

| Input | Result | Why |
| --- | --- | --- |
| `1,5` | refused | Fifteen tenths in Europe, fifteen elsewhere. Guessing transfers the wrong amount |
| `1,234.56` | refused | Two conventions that each read the other's as wrong |
| `-10` | refused | Every command this exists for is a transfer. A negative one is a withdrawal in disguise |
| `abc` | refused | Not zero — that would make `/pay bob abc` a silent no-op that looks like it worked |
| `2,500` | **accepted** | A comma with exactly three digits after it is a thousands separator in both conventions |

`parseWhole` refuses a fraction rather than truncating it. Somebody typing
`1.5` meant something, and giving them one item is not it.

### Round trip

A player reads `10M` on a scoreboard, types it into `/pay`, and gets the number
they saw:

```java
Numbers.compact(Amounts.parse("10M").orElseThrow().longValue());  // "10M"
```

---

## Dates

```java
Dates.formatMillis(stamp, Dates.Style.DATE);   // "17/08/2026"
Dates.relativeMillis(stamp);                    // "3d ago" / "in 2h"
```

| Style | Output |
| --- | --- |
| `ISO` | `2026-08-17` |
| `ISO_TIME` | `2026-08-17 14:30:05` |
| `DATE` | `17/08/2026` |
| `TIME` | `14:30` |
| `TIME_SECONDS` | `14:30:05` |
| `SHORT` | `17 Aug` |
| `LONG` | `17 August 2026` |
| `FULL` | `Monday, 17 August 2026` |

Plus `Dates.format(when, "custom pattern")` as an escape hatch, with the
formatter cached.

**Units are in the method name**, never inferred: `ofMillis`, `ofSeconds`,
`formatMillis`, `formatSeconds`. ExyliaCommons guessed the unit from the
magnitude, so any timestamp in millis before 1970-04-26 was read as seconds and
rendered as a date in the year 2286. Silently.

**The relative form comes from `TimeFormats`**, the library's one duration
formatter, so `"3d"` here and `"3d"` in a cooldown message are the same code.
Under ten seconds reads `"just now"` in both directions — many call sites render
expiries, and telling a player their mail expires "just now" when it has forty
seconds left is a lie they discover the hard way.

**Timezone** is the server machine's, so a date agrees with the clock behind
whoever reads the log next to it. An explicit `ZoneId` overload exists where it
matters.

**Locale** is fixed like everything else: `Monday`, never `lunes`, regardless of
the host.

`DateTimeFormatter` is immutable and thread-safe, so each style is built once as
a static and shared. That is the whole trick, and it is why this is cheap enough
for a placeholder.

---

## Placeholders

The same operations from any config file, no Java:

```yaml
lore:
  - '{letters}Balance {letters_black}» {success}%exylia_money_1250%'
  - '{letters}Kills {letters_black}» {info}%exylia_compact_1500%'
  - '{letters}Win rate {letters_black}» {highlight}%exylia_percent_75%'
  - '{letters}Rank {letters_black}» {info}%exylia_ordinal_3%'
  - '{letters}Last seen {letters_black}» {muted}%exylia_relative_1755400000000%'
```

A non-numeric argument resolves to `null`, so the module's `|fallback` applies
and otherwise the placeholder stays visible. Rendering `$0.00` for a missing
economy would look exactly like a broke player and hide the real problem.

---

## Migrating from ExyliaCommons

`FormatterAPI` has 189 call sites across 8 plugins.

| ExyliaCommons | ExyliaLib |
| --- | --- |
| `FormatterAPI.formatPrice(x)` | `Formats.money(x)` |
| `FormatterAPI.formatPercent(x)` | `Formats.percent(x)` — check the scale |
| `FormatterAPI.formatTime(x)` | `TimeFormats.render(x)` |
| `FormatterAPI.formatTimeClock(x)` | `TimeFormats.render(x, Style.CLOCK)` |
| `FormatterAPI.formatDateRelativeCompact(x)` | `Formats.relative(x)` |
| `FormatterAPI.formatDate(x)` | `Formats.date(x)` |

Compact output is unchanged, so menus and lore need no edits.

**Check every `formatPercent` call.** Commons did not scale its input there;
`Formats.percent` does not either — both take the hundred scale — but
`formatRatio` *did* scale, so any call site that fed it a fraction must move to
`percentOfFraction`.

Two Commons bugs are fixed rather than reproduced: the default locale leaking
into output, and `formatPrice(Object, String pattern)` silently discarding the
pattern.
