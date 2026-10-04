# Run It Back

Track your group's hardcore attempts: run timer, death log, splits, boss kills, a lifetime leaderboard, and **one-command world resets**.

Made for friend groups trying to beat the game (or every boss) on a hardcore server without anyone dying. It runs only on the server, so players join with a normal vanilla client.

## Features

- **Attempt counter and run timer.** Every world is "Run #N". The timer starts when someone moves and pauses while the server is empty.
- **Death log.** Records who died, the full vanilla death message, where, and when in the run. The death that ends the run is highlighted, and you get a big "RUN FAILED" title with the wither sound.
- **Dimension times.** When anyone in the group first entered the Nether and the End (and modded dimensions), with best times.
- **Custom splits (optional).** Add your own advancement-based milestones, such as Blaze Rod or Stronghold, and compare them against your best times.
- **Boss kills.** Ender Dragon, Wither, Warden and Elder Guardian, with who landed the final blow. Choose which bosses are required to win.
- **Per-player sidebar.** Each player picks what they see: everyone's deaths, dimensions, bosses, splits and extra stats. The run time is shown in the sidebar title. Choose a min/normal/max preset or toggle each part, and hide it entirely if you don't want it. All of it works on vanilla clients.
- **Run summary.** When a run ends you get time, deaths, splits, bosses, plus "most damage taken" and "most hostile mobs killed", with clickable buttons for the next steps.
- **History and leaderboard.** Every past run, plus lifetime stats per player: runs played and won, runs *they* ended, deaths, boss kills and damage taken.
- **Fast world reset.** `/run reset` counts down, kicks everyone, and the server comes back up on a brand new world. You can use a random seed, the same seed again, or a specific one. Datapacks are kept.
- **Shared death (optional).** When the run fails, everyone dies.

## Requirements

- Fabric server for Minecraft **26.2 – 26.3.x**
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Players need nothing installed.

## World reset: how it works

Minecraft can't swap out a loaded world, so `/run reset` stops the server, and on the next boot the mod replaces the world folder **before Minecraft loads**. Your server must therefore restart automatically after it stops:

- **Most hosting panels** have an "auto restart" option. If yours only restarts after a *crash*, set `"exitCode": 1` in the config (see below).
- **Your own machine.** Start the server with a loop:

  ```bash
  #!/bin/bash
  # start.sh
  while true; do
    java -Xmx4G -jar fabric-server-launch.jar nogui
    echo "Restarting in 2 seconds (Ctrl+C to quit)..."
    sleep 2
  done
  ```

  ```bat
  :: start.bat
  :loop
  java -Xmx4G -jar fabric-server-launch.jar nogui
  timeout /t 2
  goto loop
  ```

A reset typically takes 10–20 seconds, most of it Java startup. The old world goes to the trash and is deleted in the background, or is archived if you set `keepOldWorlds`.

If a reset ever fails (for example a permissions problem), the server refuses to start and explains why, instead of quietly booting the old world. Delete `runitback/pending-reset.json` to cancel a reset.

Interrupted resets retain the staged files and resume restoration on the next boot. The world folder and `runitback/` must be on the same filesystem for the atomic world move; otherwise the reset fails with the old world intact.

## Commands

| Command | Who | What it does |
| --- | --- | --- |
| `/run` or `/run status` | everyone | Current run, timer, deaths, splits and bosses |
| `/run deaths [run]` | everyone | Death log for this run or a past one |
| `/run splits` | everyone | Split times next to your best times |
| `/run history [page]` | everyone | All runs. Click a run to see its death log |
| `/run stats <player>` | everyone | A player's lifetime stats |
| `/run leaderboard` | everyone | Top players for runs ended, deaths, boss kills and more |
| `/run start` | op | Start the timer now |
| `/run end win` / `/run end fail` | op | Manually end the run |
| `/run reset [confirm]` | op | New world with the configured seed (random by default) |
| `/run reset same [confirm]` | op | New world with the same seed, to retry it |
| `/run reset seed <seed> [confirm]` | op | New world with a specific seed |
| `/run sidebar` | everyone | Hide or show your own sidebar |
| `/run sidebar min` / `normal` / `max` | everyone | Pick a preset (see below) |
| `/run sidebar <section>` | everyone | Turn one section on or off: `deaths`, `dims`, `bosses`, `splits`, `stats` |
| `/run sidebar default` | everyone | Go back to the server's default |
| `/run reload` | op | Reload the config file |

Resetting a run that's still in progress asks for confirmation: click **[Confirm]**, run the same command again within 30 seconds, or add `confirm` to skip the question (e.g. `/run reset confirm`).

## Sidebar

Every player has their own sidebar, and their choices are remembered across restarts and resets. The server config decides what players see before they change anything (`display.sidebar` and `display.sidebarPreset`).

| Section | Shows |
| --- | --- |
| `deaths` | One line per player with their deaths this run (crossed out once dead) |
| `stats` | Damage taken (❤ hearts) and hostile mobs killed (⚔) per player |
| `dims` | "Dimensions": when anyone first entered each dimension |
| `bosses` | "Bosses": each boss with its kill time |
| `splits` | "Splits": your custom splits with times (only if you configured any) |

Times appear in the sidebar's right-hand column, and `-` means not reached yet. Player lines show deaths and stats on the right:

```
HC Run #4  6:11
ScoPeZs          ☠0 ❤12 ⚔5
Bob              ☠1 ❤30 ⚔2
Dimensions
Nether               12:31
The End                  -
Bosses
Elder Guardian           -
Warden                   -
Wither             1:02:11
Ender Dragon             -
```

| Preset | Sections |
| --- | --- |
| `min` | deaths, stats, bosses |
| `normal` | deaths, stats, dims, bosses, splits |
| `max` | everything |

Minecraft shows at most 15 sidebar lines. If a layout needs more, the last line says how many were cut, and `/run splits` shows the full list.

## Config

`config/runitback.json` is created on first start with every option filled in. Use `/run reload` after editing it.

| Option | Default | Meaning |
| --- | --- | --- |
| `endRunOn` | `FIRST_DEATH` | `FIRST_DEATH`: any death ends the run. `ALL_DEAD`: the run ends when everyone has died. `NEVER`: deaths are only counted |
| `sharedDeath` | `false` | When the run fails, everyone else dies too |
| `spectatorOnDeath` | `true` | Dead players become spectators, even if the server isn't in hardcore mode |
| `timerStart` | `FIRST_MOVE` | `FIRST_MOVE`, `FIRST_JOIN` or `COMMAND` (`/run start`) |
| `bosses` | 4 bosses | `{ "entity", "label", "required" }`, shown in this order. Killing every `required` boss wins the run |
| `splits` | none | Optional `{ "advancement", "label" }` entries, e.g. `{"advancement": "minecraft:nether/obtain_blaze_rod", "label": "Blaze Rod"}`. Any advancement id works, including datapack ones |
| `display.sidebar` | `true` | Show the sidebar to players who haven't hidden it themselves |
| `display.sidebarPreset` | `NORMAL` | Default preset: `MIN`, `NORMAL` or `MAX` |
| `display.bossbar` | `false` | Also show the run time in a boss bar at the top of the screen |
| `display.*` | all `true` | `titles`, `sounds`, `splitMessages`, `deathCoordinates` |
| `reset.seed` | `""` | Empty means a random seed every run. Set a value to always use that seed |
| `reset.countdownSeconds` | `3` | Countdown before players are kicked |
| `reset.autoResetSeconds` | `0` | Reset automatically this many seconds after a run ends. `0` means off |
| `reset.keepOldWorlds` | `0` | Keep this many old worlds in `runitback/old-worlds/` |
| `reset.preserve` | `["datapacks"]` | Files and folders in the world folder that carry over to the new world |
| `reset.kickMessage` | | Message shown to players when the server goes down for a reset |
| `reset.exitCode` | `0` | Exit code after a reset stop. Use `1` if your host only restarts after crashes |

## Data

Everything is stored in `runitback/data.json` next to your server jar, **outside** the world folder, so it survives resets. It's plain JSON you can read, back up or graph.

## Building

```bash
./gradlew build                 # jar in build/libs, built against 26.2 (runs on 26.2 – 26.3.x)
./gradlew build -Pmc=26.3       # compile and test against 26.3
./gradlew runServer             # dev server in run/26.2, with the /hcrtest dev commands
```

## License

MIT
