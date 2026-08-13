# DevBeer — Specification

Formal specification of the plugin, one document per released version.

Where the [CHANGELOG](../../CHANGELOG.md) says *what changed*, these say *what the release
is*: its scope, features, extension points, platform APIs, compatibility contract, data
formats, test coverage and known gaps.

| Version | Name at release | Range | Marketplace | Spec |
|---|---|---|---|---|
| **2.2.0** | DevBeer | 233 – 243.\* | in review | [v2.2.0](v2.2.0.md) |
| **2.1.0** | DevBeer | 233 – 243.\* | live | [v2.1.0](v2.1.0.md) |
| **2.0.0** | Dev Utility | 241 – 243.\* | live | [v2.0.0](v2.0.0.md) |
| **1.0.0** | Spring Boot Property Viewer | 241 – 242.\* | superseded | [v1.0.0](v1.0.0.md) |

The plugin id has been `com.plugin.springprops.viewer` since 1.0.0 and never changes. See
[Constitution §I](../../.specify/memory/constitution.md).

---

## Current state (2.2.0)

### Identity

| | |
|---|---|
| Plugin id | `com.plugin.springprops.viewer` |
| Display name | DevBeer |
| Marketplace | [plugin 31085](https://plugins.jetbrains.com/plugin/31085-dev-utility) |
| Vendor | prpundge |
| Compatibility | `since-build 233` → `until-build 243.*` (IDE 2023.3 – 2024.3) |
| Built against | IntelliJ IDEA Community 2024.1.4 |

### Modules

| Module | Package | Tool window | Requires Java |
|---|---|---|---|
| Property & YAML Compare | `com.devutility.compare` | Property Compare | no |
| Folder Compare | `com.devutility.folders` | Folder Compare | no |
| JSON Formatter | `com.devutility.json` | JSON Formatter | no |
| String Toolbox | `com.devutility.strings` | String Toolbox | no |
| Spring Property Hints | `com.devutility.springprops` | — | **yes** |
| Shared config parsing | `com.devutility.props` | — | no |

Only the Spring hints need `com.intellij.modules.java`, and they sit behind an optional
dependency (`devutility-java.xml`), so everything else runs in any JetBrains IDE.

### Registered extension points

| EP | Value |
|---|---|
| `toolWindow` × 4 | Property Compare, String Toolbox, Folder Compare, JSON Formatter |
| `notificationGroup` | `DevBeer` (balloon) |
| `codeInsight.declarativeInlayProvider` | `dev.utility.spring.property.hints`, JAVA, `VALUES_GROUP` — optional descriptor only |

### Actions

| Id | Where | Purpose |
|---|---|---|
| `DevUtility.OpenCompare` | Tools → DevBeer | Open Property Compare |
| `DevUtility.OpenToolbox` | Tools → DevBeer | Open String Toolbox |
| `DevUtility.OpenFolderCompare` | Tools → DevBeer | Open Folder Compare |
| `DevUtility.OpenJsonFormatter` | Tools → DevBeer | Open JSON Formatter |
| `DevUtility.CompareSelectedFiles` | Project view | Compare 1–2 selected config files |
| `DevUtility.CompareSelectedFolders` | Project view | Compare 1–2 selected directories |
| `DevUtility.TransformSelection` | Editor menu | Searchable transform popup |
| `DevUtility.SendSelectionToToolbox` | Editor menu | Load selection into the toolbox |
| `DevUtility.FormatJson` | Editor menu | Reformat selection or file as JSON |

Action ids keep the `DevUtility.` prefix from 2.0.0. They are internal and appear only in
Keymap search; renaming them would break any user keybinding for no visible gain.

### Growth

| | 1.0.0 | 2.0.0 | 2.1.0 | 2.2.0 |
|---|---|---|---|---|
| Kotlin source files | 2 | 15 | 15 | 24 |
| Actions | 0 | 5 | 5 | 9 |
| Tool windows | 0 | 2 | 2 | 4 |
| String transforms | 0 | 71 | 71 | 74 |
| Tests | 0 | 41 | 41 | 68 |
| `sinceBuild` | 241 | 241 | 233 | 233 |

---

## Conventions used by these documents

- **Requirement ids** are `FR-<module>-<n>` for functional and `NFR-<n>` for non-functional.
  They are stable once written; a superseded requirement is struck through, not deleted.
- **Known gaps** are stated explicitly. A specification that only records intentions is a
  sales document.
- Facts are taken from the tagged commit for that version, not from memory.
