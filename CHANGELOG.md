# Changelog

## 1.0.0

First release. Minecraft 26.2 – 26.3.x, Fabric, server-side only.

- Run tracking: attempt counter, run timer, death log, splits with best times, boss kills, win/fail detection
- Per-player sidebar (vanilla clients): `/run sidebar` to hide or show it, `min`/`normal`/`max` presets, and toggles for deaths, dims, bosses, splits and stats. Server default set in the config
- Dimension times: when anyone first entered the Nether, the End and modded dimensions, with best times
- Optional custom advancement splits (none by default)
- Run time in the sidebar title; optional boss bar timer (off by default)
- Titles, sounds and an end-of-run summary
- `/run` commands: status, deaths, splits, history, stats, leaderboard, start, end, reset, reload
- World reset via restart (3 second countdown, `confirm` to skip the in-progress prompt), with a random, same or chosen seed; datapacks preserved; optional old-world archive
- Options: end on first death / all dead / never, shared death, spectator on death, auto-reset, exit code for crash-only restarts
