# Rivet

Rivet is a modular utility plugin for Paper servers. It combines survival features, staff tools, world management, player utilities, automation, and visual gameplay systems in one dependency-free runtime JAR.

[Documentation](docs/README.md) | [Getting started](docs/getting-started.md) | [Commands](docs/commands.md) | [Configuration](docs/configuration.md) | [Permissions](docs/permissions.md) | [Building](docs/building.md)

## Requirements

| Component | Version |
|---|---:|
| Paper | 1.21.11 |
| Java | 21 |
| Runtime dependencies | None |

## Features

Rivet includes:

- Homes, public warps, spawn management, teleport requests, graves, and safe random teleportation.
- Formatted chat, modern configurable death messages, friendly colors and gradients, cosmetic tags, mentions, private messages, social spy, ignore lists, nicknames, AFK state, playtime, statistics, and last-seen information.
- Kits, backpacks, daily rewards, pickup filters, trash inventories, portable workstations, and native player poses.
- Auto breeders, faster hoppers, ground-item cleanup, egg capture, villager trade rerolling, tree felling, vein mining, mob heads, holograms, and creeper-crater restoration.
- Test worlds, biome search, time and weather controls, inventory scanning, and world administration.
- Vanish, flight, coordinate teleportation, god mode, healing, moderation notes, boss bars, toasts, and optional local permissions.
- Lightweight SQLite gameplay auditing with block/container inspection, filtered lookups, retention, and rollback-ready records.
- Permission-aware interactive help, configurable messages, sounds, particles, and module-level feature switches.

See the [feature overview](docs/features.md) for module defaults and behavior.

## Installation

1. Run Paper 1.21.11 with Java 21.
2. Download or [build Rivet](docs/building.md).
3. Place the shaded JAR in the server's `plugins/` directory.
4. Restart the server.

Rivet creates `plugins/Rivet/` with global configuration, module switches, module settings, and generated runtime data.

## Command documentation

Rivet registers 98 commands. Every command has a stable heading and direct link in the [complete command reference](docs/commands.md). The documentation sidebar also links every command individually.

Common starting points:

- [`/help`](docs/commands.md#help) opens permission-aware in-game help.
- [`/sethome`](docs/commands.md#sethome) creates a home.
- [`/setwarp`](docs/commands.md#setwarp) creates a public warp.
- [`/setspawn`](docs/commands.md#setspawn) sets the server spawn.
- [`/seen`](docs/commands.md#seen) shows a compact join, login, logout, and playtime summary.
- [`/tppos`](docs/commands.md#tppos) teleports to absolute or relative coordinates.
- [`/restorationcore`](docs/commands.md#restorationcore) gives throwable crater-repair items.
- [`/log inspect`](docs/commands.md#log) toggles interactive block and container history.
- [`/rivet reload`](docs/commands.md#rivet) validates and reloads settings.

## Configuration

Rivet separates configuration by responsibility:

- `config.yml` contains plugin-wide visual settings.
- `modules.yml` enables or disables complete modules.
- `settings/` contains module options plus grouped gameplay and cross-feature policy files.
- `data/` contains generated persistent state and should not be edited while the server is running.

Read the [configuration guide](docs/configuration.md) before changing module switches or runtime data.

## Build

```bash
mvn package
```

The complete Maven package runs the test suite and writes the deployable shaded JAR to the destination configured in `pom.xml`. Do not use Maven's `clean` goal in this workspace.

See [Building from source](docs/building.md) for the full workflow.

## Metrics

Rivet uses [bStats](https://bstats.org/plugin/bukkit/Rivet/33219) for anonymous server and player counts. Server owners can use the global bStats opt-out in `plugins/bStats/config.yml`.
