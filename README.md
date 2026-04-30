# OreToggle

Paper plugin for toggling configured ore types by gradually replacing matching ore blocks in loaded chunks near online players, then restoring the exact saved block materials later.

## Commands

- `/toggleore <ore> <on|off>`
- `/restoreore <ore>`

The command names, alias, permission, and state file format remain compatible with the previous implementation.

## Configuration

- `chunks-per-tick`: completed chunks allowed per tick.
- `max-block-checks-per-tick`: block scan budget per tick.
- `restore-blocks-per-tick`: restore budget per tick.
- `player-scan-radius-chunks`: loaded chunk radius around each online player to queue, prioritized by nearest player.
- `player-scan-interval-ticks`: interval for player-driven loaded chunk discovery.
- `low-tps-threshold`: TPS value where budgets are reduced.
- `autosave-interval`: cache save interval in seconds. Use `0` or less to disable.
- `debug`: enables additional structured progress logs.

Ore removal state is cached in memory at runtime and saved to `ore-state.yml` on autosave and plugin shutdown.
