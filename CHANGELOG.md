# Changelog

## 2.2.0

### Added

- **Folder Compare** tool window. Compares two directory trees file by file, by content hash
  rather than size and timestamp, running in the background and cancellable.

  The reason it exists alongside the IDE's own directory diff is the extra column: for
  `.properties` and YAML pairs it reports *how the keys differ* — "2 changed, 1 only left" —
  without opening anything, and double-clicking one opens the key-level Property Compare
  instead of a text diff. A text diff of YAML against `.properties` tells you nothing.

  Reachable from `Tools → DevBeer → Compare Folders…` or by selecting two folders in the
  Project view. Skips `build`, `target`, `node_modules`, `.git` and similar by default.

- **JSON Formatter** tool window: pretty print (2 spaces, 4 spaces or tabs), minify, sort keys
  at every level, and validate.

- **Format JSON** editor action — reformats the selection, or the whole file, as one undoable
  change.

- Three transforms in the String Toolbox: lenient JSON pretty print, JSON sort keys, and
  JSON validate.

### Changed

- **JSON is parsed now, not just reindented.** The old pretty-printer walked the text moving
  whitespace around, which meant malformed input produced malformed output with no
  explanation. A recursive-descent parser replaces it, and that buys three things:

  - **Diagnostics.** Errors carry a reason plus a line and column, and the editor caret lands
    on the offending character.
  - **Losslessness.** Numbers keep their original text and objects keep an ordered list of
    entries, so `1.0`, `1e3`, `0.1000` and duplicate keys round-trip unchanged rather than
    being silently normalised.
  - **Lenient mode**, which accepts the JSON people actually write: `//` and `/* */` comments,
    trailing commas and single-quoted strings.

## 2.1.0

### Changed

- **Renamed to DevBeer**, with a new beer-glass logo (40×40 Marketplace icon and 13×13 menu
  mark, each in a light and a dark variant). The plugin id is unchanged, so this is an
  in-place update — nothing to reinstall and no settings lost.
- **Minimum supported IDE lowered from 2024.1 to 2023.3.** That is the floor, not a
  preference: 2023.3 is the first build with the declarative inlay-hints API the Spring
  hints are built on. See the README for what reaching further back would cost.

### Fixed

- `SpringPropertyIndex.getInstance` used the reified `project.service<T>()` helper, which
  inlines a call to `ServicesKt.serviceNotFoundError` — a method that does not exist before
  2024.1. On 2023.3 this would have thrown `NoSuchMethodError` on every inlay hint. Replaced
  with `getService(Class)`. Caught by the Plugin Verifier when the floor was lowered.

## 2.0.0

The plugin became a multi-tool — a home for everyday developer utilities rather than a
single-feature plugin. (Released under the name *Dev Utility*.) The plugin id is unchanged,
so this is an update to the existing
Marketplace listing.

### Added

- **Property & YAML File Compare** tool window. Key-by-key comparison of two configuration
  files, including `.properties` against YAML, with an interactive table, filtering,
  jump-to-source on double-click, and normalized or raw side-by-side views in the IDE's own
  diff editor. Reachable from `Tools → DevBeer` or the Project view context menu.
- **String Toolbox** tool window with 60+ transformations across case conversion,
  encoding, hashing, JSON/XML, line operations, text analysis, config-format conversion and
  time/ID helpers, with a live preview and editor round-trip buttons.
- **Transform Selection…** and **Send Selection to String Toolbox** editor actions.
- `Tools → DevBeer` menu group.
- Unit tests for the parsers, the diff engine and the transformation catalog.

### Fixed

- The YAML reader is a real indentation parser now. The previous one reset its state on any
  dedent, so in a file with more than one nested block every key after the first block was
  silently missed. Sequences, sequences of maps, multi-document files, block scalars and flow
  collections are handled as well.
- `@ConfigurationProperties` is actually implemented. It was advertised in the 1.0.0
  description but never resolved anything; it now reports the keys bound under the prefix and
  resolves each field individually, honouring Spring's relaxed binding.
- Property lookups are cached per project. Every inlay hint used to re-scan and re-parse every
  configuration file in the project, which made large projects crawl.

### Changed

- Java support is now an *optional* dependency, so the compare and string modules work in
  every JetBrains IDE rather than only the Java-based ones.
- Plugin logo and tool window icons added.
- Sources moved from `com.plugin.springprops` to `com.devutility`.

## 1.0.0

- Initial release: Spring Boot `@Value` inlay hints.
