# Better Than Village — implementation notes

Clean-room NeoForge 1.21.1 reimplementation of the behavior described in
`docs/independent-villagers/` (the "spec"). The spec was written for the original Fabric
mod on MC 1.21.11 with Yarn names; this project uses **Mojang mappings on MC 1.21.1**.

## Ground rules

- Behavior comes from the spec only. Never read, decompile or copy anything from `inspiration/`
  (the original jar) — it is "All Rights Reserved". No original assets (structure NBTs, template
  pools) are shipped; house blueprints use **vanilla** village templates
  (`minecraft:village/<biome>/houses/...`).
- Names: mod id / namespace `better_than_village` (spec: `smart_colonies`), package
  `net.betterthanvillage` (spec: `net.smartcolonies`), command root `/btv` (spec:
  `/smartcolonies`), command tags `btv_*` (spec: `smart_colonies_*`), item custom-data keys
  `better_than_village_*` (spec: `smart_colonies_*`). NBT keys inside our own saved data keep the
  spec names.
- Code comments in English. Russian villager status strings from the spec are kept verbatim
  (they are only shown by `/btv status`); player-facing chat goes through lang keys
  (`en_us.json` + `ru_ru.json`).
- Brain tasks must live under `net.betterthanvillage.*`: the controller treats every running
  behavior whose class name starts with that prefix as a "mod task" (stopped at night, counted
  by the watchdog, etc.).

## Build and test

- `./gradlew build` produces the jar in `build/libs/` (needs JDK 21).
- `./gradlew runClient` / `./gradlew runServer` start a dev game with the mod loaded.

## Package map

| Package | Owner | Content |
|---|---|---|
| `config` | core | `ColonyConfig` (01 §2) |
| `colony` | core | `Colony` (+NBT), `ColonySavedData`, `ColonyManager` (registration, membership, world tick), `ColonyStorage` (stockpile ops), `Census`, `RoleBalancer`, `TerrainSurvey`, `SpawnSpots`, `StoragePlacer`, `StorageScanner`, `Protection`, `SafeEditScope`, `PathBudget`, `BellScanner` |
| `ai` | core | `VillagerController` (mobTick logic, 02 §2.2), `ColonyBrain` (activity task lists), `ColonyTask` (base class for work tasks), `ColonyGate`/`MovementGate`, `VillageStorageSensor`, `StarterWarehouseTask`, `VillagerUtil` |
| `ai.work` | core | `WorkTracker` (status/credit/watchdog), `BlockWorkTimer` (break timing), `SafeEdit` (safe break/place), `ToolSupply` (tool tiers, smelting, buckets) |
| `ai.nav` | core + gather | `Nav` (standard approach 03 §5.1, core); `DoorHandler`, `AntiStuck`, `WaypointNavigator` (gather) |
| `defense` | defense | guards, golems, threat manager, raid shelter |
| `gather` | gather | lumberjack, miner, night lighting |
| `food` | food | farmer, fisher, rancher, item pickup |
| `construction` | construction | house planning/catalog/builder, roads, walls, damage detection & repair |
| `society` | society | economy, trading, deeds, land notices, theft, offline sim, colonization, film mode, commands |
| `mixin.<subsystem>` | each | mixins, registered in `better_than_village.<subsystem>.mixins.json` (`client` mixins in `better_than_village.client.mixins.json`) |

## Core API cheat sheet

- `ColonyManager.colonyAt(level, pos)`, `foundingPartyColony(level, uuid)`, `colonyOf(level, villager)`,
  `members/adultMembers(level, colony)`, `markDirty(level)`, `terrainChangesAllowed(level)`,
  `data(level)` -> `ColonySavedData` (player blocks, land plots, land warnings).
- `Colony`: all persisted fields of spec 01 §5.2 as public fields; `roleOf/setRole`, `storages`
  (runtime storage list), `managedStorages`, `jobs`, `repairs`, `fortifications`, `runtime(key, factory)`
  for free-form runtime state.
- `ColonyStorage`: `deposit`, `withdrawOne`, `takeFrom`, `count`, `has`, `consume`, `consumeNutrition`,
  `transaction`, `freeCapacity`, `hasRoomFor`, `emptySlots`, `nearest*`, `recount`, `foodValue`,
  `usableFor(level, colony, role, allKinds)`.
- `Protection.terrainModifiable(level, pos)` (spec 03 §1.4 / 01 §6.7), `isProtectedContainer`.
- `SafeEdit.breakBlock/place`, `SafeEditScope.run/call` (edit suppression for the griefing listener).
- `Nav.approach(level, v, pos, speed[, r2])`, `Nav.standable`, `Nav.currentTarget`, `Nav.blacklist`, `Nav.stuck`, `Nav.reset`.
- `WorkTracker.status/credit/progress/deliver`.
- `BlockWorkTimer.tick(level, actor, pos, visuals)` / `tickAction(...)`.
- `ToolSupply.ensureTool(level, colony, v, Kind.AXE|PICKAXE|HOE)`, `smeltTo`, `smeltAll`, `ensureBucket`.
- `PathBudget.tryAcquire(level)` — the shared 6-per-tick probe limiter.
- `RoleBalancer.applyRole`, `Census.populationGate`, `SpawnSpots.find/createVillager`.
- Registries: `ModRegistries.COLONY_WORK/COLONY_SHELTER/COLONY_TRAVEL`, memories
  (`BUILD_TARGET_POS`, `TREE_TARGET_POS`, …, `THREAT_TARGET`), `ModTags.BUILDER_PROTECTED`, `ModTags.GOLEM_ATTACK`.

## Stubs

Every subsystem entry point the core calls already exists as a stub with its final signature
(e.g. `defense.ThreatManager.findThreat`, `construction.Fortifications.isWallBlock`,
`society.OfflineSim.catchupStep`). Owners replace the bodies and may add methods, but must keep
the existing signatures (other subsystems call them).
