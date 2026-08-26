<img width="2172" height="724" alt="image" src="https://github.com/user-attachments/assets/649843a1-af3c-4c49-a728-e92f78eb38b1" />

<h1 align="center">Rivet</h1>

<p align="center">
  <strong>Everything your Paper server needs, held together.</strong>
</p>

<p align="center">
  <img alt="Paper 1.21.11" src="https://img.shields.io/badge/Paper-1.21.11-2E8B57?style=for-the-badge">
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-E76F00?style=for-the-badge">
  <a href="https://github.com/MineWing"><img alt="MineWing" src="https://img.shields.io/badge/MineWing-Plugin_Suite-2563EB?style=for-the-badge&logo=github"></a>
</p>

<p align="center">
  <a href="docs/getting-started.md">Getting started</a> ·
  <a href="docs/features.md">Features</a> ·
  <a href="docs/commands.md">Commands</a> ·
  <a href="docs/configuration.md">Configuration</a> ·
  <a href="docs/permissions.md">Permissions</a>
</p>

Rivet is a modular utility suite for modern Paper servers. It brings survival essentials, community tools, staff controls, automation, world management, auditing, and polished visual feedback into one configurable JAR—with no required runtime plugins.

## One plugin, a complete toolkit

| Area | Highlights |
|---|---|
| **Travel & survival** | Homes, public warps, spawn, teleport requests, safe random teleportation, graves, kits, backpacks, daily rewards, and portable workstations |
| **Players & community** | MiniMessage chat, tags, nicknames, mentions, private messages, ignore lists, AFK state, polls, playtime, statistics, and last-seen information |
| **Automation & quality of life** | Auto breeders, faster hoppers, item magnets, pickup filters, tree felling, vein mining, egg capture, mob heads, and villager trade rerolling |
| **World tools** | Test worlds, biome search, time and weather controls, inventory scanning, creeper confetti, and crater restoration |
| **Staff & moderation** | Vanish, flight, inventory tools, moderation history, notes, holograms, boss bars, toasts, and optional local permissions |
| **Safety & visibility** | SQLite inventory snapshots, searchable backups, gameplay auditing, block/container inspection, configurable retention, and rollback-ready records |

Every major system can be switched independently in `modules.yml`, while feature-specific settings stay separated under `settings/`. Messages, sounds, particles, titles, boss bars, and other feedback are configurable instead of being buried in code.

## Quick start

### Requirements

| Component | Version |
|---|---:|
| Paper | 1.21.11 |
| Java | 21 |
| Required plugins | None |
| Optional integration | PlaceholderAPI |

1. Build Rivet with `mvn package` or download a published build.
2. Place the shaded JAR in the server's `plugins/` directory.
3. Restart Paper.
4. Review `plugins/Rivet/modules.yml` and the generated `settings/` files.
5. Use `/rivet reload` after making reload-safe configuration changes.

Rivet generates a structured `plugins/Rivet/` directory:

```text
plugins/Rivet/
├── config.yml       # Global presentation and plugin-wide settings
├── modules.yml      # Feature switches
├── settings/        # Settings grouped by module or responsibility
└── data/            # Generated persistent state
```

Do not edit files under `data/` while the server is running. The [configuration guide](docs/configuration.md) explains reload behavior and every settings group.

## Useful starting commands

| Command | What it does |
|---|---|
| [`/help`](docs/commands.md#help) | Opens permission-aware interactive help |
| [`/sethome`](docs/commands.md#sethome) | Creates a personal home |
| [`/setwarp`](docs/commands.md#setwarp) | Creates a public server warp |
| [`/setspawn`](docs/commands.md#setspawn) | Sets the server spawn |
| [`/seen`](docs/commands.md#seen) | Shows join, login, logout, and playtime information |
| [`/snapshot`](docs/commands.md#snapshot) | Browses, searches, exports, and restores inventory backups |
| [`/log inspect`](docs/commands.md#log) | Toggles interactive block and container history |
| [`/rivet reload`](docs/commands.md#rivet) | Validates and reloads supported settings |

Rivet has an extensive command surface, so the README intentionally stays short. Use the [complete command reference](docs/commands.md) for syntax, aliases, permissions, and direct links to every command.

## Documentation

- [Feature overview](docs/features.md) — modules, defaults, and behavior
- [Getting started](docs/getting-started.md) — installation and first configuration
- [Commands](docs/commands.md) — complete command reference
- [Configuration](docs/configuration.md) — file layout and reload rules
- [Permissions](docs/permissions.md) — permission nodes and defaults
- [Building](docs/building.md) — Maven build and deployment workflow

## Build from source

```bash
git clone https://github.com/MineWing/Rivet.git
cd Rivet
mvn package
```

The Maven package runs the test suite and writes the deployable shaded JAR to the destination configured in `pom.xml`. In this workspace, do not use Maven's `clean` goal.

## Metrics

Rivet uses [bStats](https://bstats.org/plugin/bukkit/Rivet/33219) for anonymous server and player counts. Server owners can opt out globally in `plugins/bStats/config.yml`.

---

<p align="center">
  Built by <a href="https://github.com/MineWing">MineWing</a> · See also <a href="https://github.com/MineWing/EveryBlock">EveryBlock</a> and <a href="https://github.com/MineWing/one-million-crops">OneMillionCrops</a>
</p>
