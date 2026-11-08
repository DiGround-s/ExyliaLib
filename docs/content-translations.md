# Content translations

Since 1.529.0. `Languages.translate` lays a language over a plugin's catalogue: text that lives in
the server's own files (a job's name, a kit's lore, a crate's description) rather than under
`lang/<code>/`. Nothing is written to disk, so servers already installed get the translations from
the jar on their next start, and an owner's edit always wins.

## What happens

The plugin ships `lang/<code>/content/<catalogue>.yml` next to its other language files:

```yaml
# lang/es/content/jobs.yml, over the job files jobs/explorer.yml, jobs/miner.yml...
explorer:
  name: 'Explorador'
  description:
    - 'Pisa primero tierras nuevas.'
    - 'Los biomas raros pagan más.'
  rewards.10.0.name: '{info}250 monedas'
  perks.10.a.name: 'Vagabundo'
```

Between reading a file and using it, the plugin calls:

```java
YamlConfiguration yaml = new YamlConfiguration();
yaml.load(file);
Languages.translate(plugin, MyPlugin.class, "jobs/explorer.yml", yaml);
```

Each key of the table is then taken in turn:

| The server's value | Result |
| --- | --- |
| equals what the plugin shipped | the translation |
| anything else (the owner edited it) | the server's value |
| absent (the owner deleted it) | stays absent |
| no translation for it, or no table for the language | the server's value, never a key |
| the translation loses a `%placeholder%` or a `{token}`, is empty, or is a line where a list belongs | the server's value, and a warning naming the key |

**Keys.** The path of the text inside its file. The first folder of the resource names the catalogue
and the rest prefixes the keys: `jobs/explorer.yml` reads `content/jobs.yml` under `explorer`, and a
single `kits.yml` reads `content/kits.yml` by its own paths (`kits.starter.name`). Numbers step into
lists (`rewards.10.0.name`). Nested and dotted YAML are the same thing, so write whichever reads best.
A list is replaced whole and may change its number of lines.

**The language** is `Languages.code(plugin)`, the one the plugin's messages use, per plugin rather
than per player: ExyliaLib has no per-player language. A reload that reads the file again picks up a
changed language.

**What counts as shipped.** The jar's copy of `resource`, and for a file kept up to date with
`BundledFiles.refresh` the defaults reviewed in `.defaults/files/` too, so rewording a default in a
later version does not read as the owner's edit. A plugin that only writes its files on first start
has no such record: after rewording a default, servers holding the old text see that old English
until they edit it. Reword catalogue text in such a plugin only when it matters, or move it to
`BundledFiles`.

**Owners.** A file of the same shape at `plugins/<Plugin>/lang/<code>/content/<catalogue>.yml` is laid
over the jar's table, for a language the plugin does not ship or to correct a word everywhere.

## Adopting it

1. Find where the catalogue is read for use, and call `Languages.translate` right after reading it.
   Skip it where the file is read to be written back (editors, importers): those must see the file as
   it is, or saving would write the translation into the owner's file. Read the file afresh there.
2. Write `lang/<code>/content/<catalogue>.yml` for every language in `src/main/resources/lang/`
   except `en`. Only what a player reads, with the same `{tokens}` and `%placeholders%`. Keep to about
   34 visible characters per lore line.
3. Add a test, so a mistake fails the build rather than warning on a server:

   ```java
   @Test
   void translationsMatchTheCatalogue() {
       for (String code : List.of("de", "es", "fr", "pt", "ru")) {
           for (String id : List.of("explorer", "miner")) {
               assertEquals(List.of(), Languages.translationProblems(MyPlugin.class, code, "jobs/" + id + ".yml"));
           }
       }
   }
   ```

4. Render the screens in a long language (German, Russian) and read the wrapping.

Reading text from a record file (`Configs.define`) or a database? This does not reach it yet: it
works on a `ConfigurationSection`. ExyliaJobs is the reference (`ExyliaJobs#loadCatalogue`,
`lang/<code>/content/jobs.yml`, `ShippedContentTest`).

## Source and tests

`config/Languages` (`translate`, `translationProblems`), `config/ContentOverlay`;
`config/ContentTranslationTest`.
