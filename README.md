# Better Than Village

NeoForge 1.21.1 mod that turns every generated vanilla village into a self-sufficient colony:
villagers get jobs by demand (builder, militia, lumberjack, miner, farmer, fisherman, rancher,
elder, founder), share categorized storage, build houses, roads and walls, repair damage, defend
themselves, trade from colony stock, sell deeds and land, punish theft and griefing, catch up while
unloaded and found new colonies.

Clean-room reimplementation of the behavior documented in `docs/independent-villagers/`.
Implementation notes: `docs/dev/ARCHITECTURE.md`.

## Build

Requires JDK 21.

```sh
./gradlew build   # jar in build/libs/
```

Configuration: `config/better_than_village.json` (created on first start).

## Commands

- `/btv status` — colony dump at your position.
- `/btv jobs` — construction plots, roads, walls, builder targets.
- `/btv repair` (op) — audit completed houses and queue repairs.
- `/btv house <biome> [template] [floor]` (op) — queue a house.
- `/btv film ...` (op) — staged-demo tools (`on`, `supply`, `population`, `develop`, `expedition`, `rebalance`, …). Use on a world copy.

## Differences from the original

- Houses use vanilla village templates; the original mod's custom house NBTs and worldgen
  overrides are not included.
- Mod id `better_than_village`, command `/btv`.
