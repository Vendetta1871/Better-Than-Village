# Better Than Village — implementation notes

Clean-room NeoForge 1.21.1 reimplementation of the gameplay of the Independent Villagers
Fabric mod (MC 1.21.11). This project uses **Mojang mappings on MC 1.21.1**.

## Ground rules

- Behavior comes from a written behavior spec only (kept outside this repository). Never read, decompile or copy anything from `inspiration/`
  (the original jar) — it is "All Rights Reserved". No original assets (structure NBTs, template
  pools) are shipped; house blueprints use **vanilla** village templates
  (`minecraft:village/<biome>/houses/...`).
- Names: mod id / namespace `better_than_village` (original: `smart_colonies`), package
  `net.betterthanvillage` (original: `net.smartcolonies`), command root `/btv` (original:
  `/smartcolonies`), command tags `btv_*` (original: `smart_colonies_*`), item custom-data keys
  `better_than_village_*` (original: `smart_colonies_*`). NBT keys inside our own saved data keep the original
  names.
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
| `colony` | core | `Colony` (+NBT), `ColonySavedData`, `ColonyManager` (registration, membership, world tick), `ColonyStorage` (stockpile ops), `Census`, `Professions` (fixed professions, elder, inheritance, looks), `Territory` (noisy territory shape), `Domain` (per-chunk country ownership), `TerrainSurvey`, `SpawnSpots`, `StoragePlacer`, `StorageScanner`, `Protection`, `SafeEditScope`, `PathBudget`, `BellScanner` |
| `ai` | core | `VillagerController` (mobTick logic, 02 §2.2), `ColonyBrain` (activity task lists), `ColonyTask` (base class for work tasks), `ColonyGate`/`MovementGate`, `VillageStorageSensor`, `StarterWarehouseTask`, `VillagerUtil` |
| `ai.work` | core | `WorkTracker` (status/credit/watchdog), `BlockWorkTimer` (break timing), `SafeEdit` (safe break/place), `ToolSupply` (tool tiers, smelting, buckets) |
| `ai.nav` | core + gather | `Nav` (standard approach 03 §5.1, core); `DoorHandler`, `AntiStuck`, `WaypointNavigator` (gather) |
| `defense` | defense | guards, golems, threat manager, raid shelter |
| `gather` | gather | lumberjack, miner, night lighting |
| `food` | food | farmer, fisher, rancher, item pickup |
| `construction` | construction | house planning/catalog/builder, roads, layered walls, damage detection & repair; v2 phase 2: `LandWork`/`LandWorkTask` (miner demolition and pit filling), `LampPosts`/`LampPostTask` |
| `economy` | economy (v2 phase 1) | `Commissions` (public works budget), `Household`/`Households`, `StockScope`, `Values`/`Needs`/`Market`/`Gathering`, `Treasury` (taxes, wages), `Debt`/`Credit`, `Conscription`, `BirthOrder`/`Births`, `Workshop`/`WorkshopTask`, `Chests` |
| `society` | society | player trading (`Trading`, `PlayerCredit`, `TreatyTrade`), deeds, `LandSales` (deed maps, empty houses), land notices, theft, offline sim, colonization, film mode, commands |
| `law` | law (v2 phase 3) | `Law` (offences, witnesses, warnings via vanilla gossip, escalation), `Witnesses`, `Offence`, `Wars`/`PlayerWar` (a country's war on a player, treaties and truces, losses, weariness), `Surrender` (white flag, ceasefire, perfidy, the elder's walk), `Treaties` (price, refusal, reparation, treaty book), `LawEvents` |
| `world` | world (v2 phase 4) | `Materializer` (stores travelling entities and spawns them again), `WorldScheduler` (time-budgeted steps of abstract villages, caravans, politics), `Ledger`/`Ledgers` (virtual chests of abstract villages, delta application on load), `AbstractEconomy` (gatherings on ledgers, market cleared on a worker), `Production` (a day of work per trade), `Caravan`/`Caravans` (treasury trade and order deliveries, abstract travel and live materialization), `SimWorkers` (worker threads for snapshot computation) |
| `politics` | politics (v2 phase 4) | `Country`/`Countries` (capital by founding order, succession, ruler, strength, join/secede), `Politics` (daily step, loyalty, the ruler's patience), `Orders`/`Order` (redistribution, temporary tax, mobilization), `Diplomacy`/`Relation` (relations, alliance/peace/hostility/war, causes, declaration by strength), `Borders`/`BorderOffer` (buying border chunks for a search party), `PoliticsCommands` (`/btv country`, `/btv world`) |
| `warfare` | warfare (v2 phase 5) | `Campaign`/`Campaigns` (armies: mobilization, march, outpost, siege, return; soldiers stored and materialized), `Battle` (abstract strength model), `Siege`/`SoldierTask` (live sieges: breaches, tunnels, staircases), `Outposts` (camps and border watch), `Conquest`, `Casualties` (deaths of unloaded villagers), `Weariness` (war weariness and peace terms), `Army`/`WarriorClass` (classes, shifts, horses, cavalry), `NightOps`/`NightOpTask` (night operations), `PlayerBases`/`PlayerIntel`/`PlayerRaid` (war on a player's base), `WarfareCommands` (`/btv war`) |
| `debug` | tests | `TestBot`: a fake survival player for headless tests, driven by `/btv bot` (see `society.BotCommands`) |
| `mixin.<subsystem>` | each | mixins, registered in `better_than_village.<subsystem>.mixins.json` (`client` mixins in `better_than_village.client.mixins.json`) |

## Stock scope (v2)

Every `ColonyStorage` call works on the stock in scope (`economy.StockScope`): during a villager's AI step
(`VillagerMixin` HEAD..TAIL) that is the villager's household (its home chests); everything else, and colony
tasks whose `ColonyTask.publicWork(v)` is true (houses, repairs, roads, walls, warriors' gear; switched by
`BehaviorScopeMixin`), uses the treasury (`colony.storages`, the village stock without home chests). Use
`StockScope.treasury(...)` for village-level work inside a villager step, `ColonyStorage.treasury(...)` /
`usable(level, household)` for explicit chest lists. `ColonyStorage.recount` always counts all village stock.
`colony.roleOf` is the villager's duty (warrior while conscripted), `colony.tradeOf` its profession for life.

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
- `Professions.applyRole/assign/maintainElder/inherit`, `SpawnSpots.find/createVillager`.
- Registries: `ModRegistries.COLONY_WORK/COLONY_SHELTER/COLONY_TRAVEL`, memories
  (`BUILD_TARGET_POS`, `TREE_TARGET_POS`, …, `THREAT_TARGET`), `ModTags.BUILDER_PROTECTED`, `ModTags.GOLEM_ATTACK`,
  `ModTags.WEAPONS`, `ModTags.SURRENDER_FLAGS`, `ModTags.PROTECTED_ANIMALS`.
- World: `ColonyManager.isLive(level, colony)` (live vs abstract); every chest access of `ColonyStorage` goes through
  `Ledgers.virtual(level, pos)` first, so code that reads chests by position directly (`level.getBlockEntity`) bypasses
  the ledger and must only run for live villages. `Caravans.homeOf/away/isTraveller` (a caravan leader keeps its home
  colony far away and only travels).
- Politics: `Countries.get/capital/ruler/villages/strength/join/secede`, `Diplomacy.relation/state/trades/declareWar/makePeace`,
  `Orders.issue`, `Borders.acquire`.
- Warfare: `Campaigns.away/homeOf/enemyOf/defenderOf/launch/retreat` (a soldier on campaign belongs to its home village
  wherever it stands; `ThreatManager.enemySoldier` makes it a threat to the village it attacks), `Battle.defenders`,
  `Fortifications.builtRings`, `Casualties.killDefender/killVillager`, `world.Materializer` (stash and spawn of travellers,
  shared with caravans).
- Law: `Law.subject/warningActive`, `Wars.war/hostile/declare/peace`, `ThreatManager.engage/calm`
  (players are never alarm attackers: `ThreatManager.isWanted` means at war with the country and no white flag).

## Stubs

Every subsystem entry point the core calls already exists as a stub with its final signature
(e.g. `defense.ThreatManager.findThreat`, `construction.Fortifications.isWallBlock`,
`society.OfflineSim.catchupStep`). Owners replace the bodies and may add methods, but must keep
the existing signatures (other subsystems call them).
