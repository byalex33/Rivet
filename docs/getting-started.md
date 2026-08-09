# Getting started

## Install Rivet

1. Install Paper 1.21.11 with Java 21.
2. Obtain or [build the Rivet JAR](building.md).
3. Place `rivet-1.0-SNAPSHOT.jar` in the server's `plugins/` directory.
4. Start or restart the server.
5. Confirm that `plugins/Rivet/` was created and that Rivet enabled without errors.

Rivet does not require a database or another plugin at runtime.

## First configuration

Rivet creates three configuration areas:

- `config.yml` contains plugin-wide visual settings and migration state.
- `modules.yml` enables or disables complete modules.
- `settings/` contains one settings file for each module.

Generated state is stored under `data/`. Do not edit files in `data/` while the server is running.

See [Configuration](configuration.md) for the complete layout.

## Enable or disable a module

1. Stop the server.
2. Open `plugins/Rivet/modules.yml`.
3. Change the relevant module value to `true` or `false`.
4. Start the server.

Module changes require a restart because listeners and scheduled tasks are registered during startup. Other settings can normally be applied with [`/rivet reload`](commands.md#rivet).

## Verify permissions

Most player-facing commands are available by default. Administrative commands default to server operators. Rivet can use its own permission module, or another permission plugin can grant the nodes declared in `plugin.yml`.

See [Permissions](permissions.md) for the complete reference.

## Useful first commands

- [`/help`](commands.md#help) displays permission-aware in-game command pages.
- [`/rivet`](commands.md#rivet) displays plugin information and reloads settings.
- [`/setspawn`](commands.md#setspawn) sets the server spawn.
- [`/sethome`](commands.md#sethome) creates a player home.
- [`/setwarp`](commands.md#setwarp) creates a public warp.
