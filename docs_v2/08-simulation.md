# 08 — Live and abstract simulation

## Why

Minecraft ticks only loaded chunks. Villages are hundreds of blocks apart and usually unloaded.
Caravans, search parties, armies and whole economies must keep running without a player, so
**most inter-village activity is computed as numbers**. Real entities and blocks are used only
near a player.

## Two modes

| | Live | Abstract |
|---|---|---|
| When | The village's core is entity-ticking (a player nearby) | Otherwise |
| Villagers | Real entities with brains and tasks | Rows in the village ledger |
| Market | Real gathering at the bell | Daily formula: production, consumption, clearing on ledgers |
| Construction | Block by block | Progress per day; blocks are placed on load |
| Travelling groups (caravans, parties, armies) | Real entities walking | A position moving along a route at an average speed |
| Battles | Real combat | Strength model ([07](07-warfare.md)) |
| Night operations | A real agent sneaking in | Rolled against the night watch ([07](07-warfare.md)) |
| Debts | Repaid at real gatherings | Repaid in the daily formula |

## World scheduler

- **One scheduler** in the overworld's saved data advances every abstract village, caravan,
  party and army at a low rate (default: every in-game hour). It does not depend on any chunk
  being loaded. Its work is spread over ticks and threads (see "Spreading the work").
- **Time source.** World game time, so time while the server is stopped is not simulated, as
  in the old catch-up.
- **What it advances:**
  - production and consumption;
  - the market;
  - taxes;
  - births and deaths;
  - construction progress;
  - travel and battles;
  - loyalty and relations.

## Ledgers

**Problem:** an unloaded village's chests cannot be changed without loading their chunks.

**Solution:** every household and treasury has a **ledger**: the expected contents plus
**pending deltas**.
- **Abstract mode** changes only the ledger.
- **On load**, pending deltas are applied to the real chests, gradually, as in the old "arrival"
  step. Shortages are clamped: you cannot remove what is no longer there, for example if a player
  stole it. The ledger is then re-synced from the real chests.
- **Live mode** keeps the ledger in sync with every chest change.

Live and abstract modes use **the same accounting rules**, so switching between them never
creates or destroys goods or emeralds.

## Materialization

- **Village loads.** Apply ledger deltas, place pending construction blocks (bounded per tick),
  then spawn newborns and remove the dead.
- **Group enters loaded chunks.** A travelling group spawns as real entities at its abstract
  position (on the nearest safe ground). When the group leaves loaded chunks, its entities are
  stored back into the abstract record and removed.
- **Player meets a battle.** The battle switches to live mode with the surviving forces.

## Reuse

- The offline catch-up of the `legacy` port is
  the starting point for the economy formulas and the gradual "arrival".
- Changes: the simulation runs continuously instead of in one catch-up on return, the 6-day cap
  goes away, and it is per household instead of per colony.

## Spreading the work

Nothing heavy may run all in one tick. Three rules:

1. **Time-slicing on the server thread.** Everything that touches the world (blocks, entities,
   chests) runs on the server thread, in small steps with a **per-tick time budget** (default
   ~2 ms in total, configurable).
   - Each job keeps its own cursor and resumes next tick: e.g. the abstract step of village N,
     placing materialized blocks, scanning a territory.
   - Villages are processed **round-robin**, each with an offset, so their gatherings, censuses
     and abstract steps do not all fall on the same tick.
2. **Async computation on snapshots.** Pure computation runs on a worker thread pool, never on
   the server thread. The server thread takes an **immutable snapshot** (ledgers, prices,
   positions, a copy of the needed block data), hands it to a worker, and applies the result
   later:
   - market clearing;
   - the daily abstract economy;
   - battle resolution;
   - route planning for caravans and armies;
   - site and base search;
   - war and diplomacy decisions.
3. **Validation when applying.** Results are checked against the current state before being
   applied. A changed ledger version, a moved entity or a broken block means the result is
   dropped and the job retried. Workers never read or write live world objects.

Long scans of blocks, for example finding the player's base or a site for a new village, use
cached data (the player-block registry, heightmaps) or are split over many ticks. They never
load chunks just to look.

## Debugging

- **Commands.** New `/btv` commands:
  - inspect a household, a market and a country;
  - force an abstract step;
  - force a village or group to materialize.

  Headless server tests ([`tests/servertest.py`](../tests/servertest.py)) use them to check
  phase "done" criteria.

## Implementation notes (phase 4)

The `world` package.
- **Live or abstract.** A village is live while its bell's chunk is entity-ticking or a member
  ticked a moment ago (`ColonyManager.isLive`); otherwise it is abstract.
- **Ledgers** (`Ledger`, `Ledgers`). Each village has one ledger, covering the treasury and every
  home chest.
  - While the village is live, the base contents of the loaded chests are captured every 10 s.
  - When the village goes abstract, still-loaded chests are captured once more and every chest
    gets a virtual copy.
  - `ColonyStorage` asks `Ledgers.virtual` before it touches a real chest. So all accounting code
    (market, taxes, wages, credit, caravans) works on the virtual chests unchanged.
  - When the village is live again, each loaded chest gets `current − base`: removals are clamped
    to what is still there, and additions that do not fit are dropped at the chest. Chests in
    unloaded chunks stay virtual until they load.
- **Scheduler** (`WorldScheduler`). It runs every tick within `world.tick_budget_ms` (2 ms):
  - applies finished worker results;
  - runs village steps, every `world.step_ticks` (1000) with a per-village offset;
  - moves caravans;
  - runs the countries' daily step.

  The old absence catch-up is off for settled villages. Only the initial development of a
  generated village and the placement of construction progress on load remain.
- **Abstract economy** (`AbstractEconomy`, `Production`). The village clears the same gatherings
  as a live village, all households attending, up to 8 missed ones after a long absence.
  - In the morning every household eats, wages are paid, interest and defaults run, and every
    worker does a day of work from its own chests: inputs, the tool of its trade (worn out every
    8 days) and products, which pay the levy in kind.
  - **Births and conscription** run as in a live village, from the records: every household is
    home, and a child is a household whose trade is still pending. Parents order births and pay
    from their ledger chests (at most 4 open orders). A mason's day goes to the oldest birth house
    first (480 blocks of labour finish it), then to the village's unfinished construction.
  - The labour is placed by the catch-up builder when the village loads: a finished birth house
    gets its site then and is placed at once, and the child is born by its bed, as old as the time
    since the house was finished (an adult after a day). In a live village a catch-up house that
    makes no progress for a minute (no material, an obstacle) goes to the masons for good.
  - **Search parties** of crowded abstract villages travel as records ([04](04-land.md)).
  - Order matching runs on a worker thread on copies of the chests. Loans reserve a slot for
    their note with a placeholder. The result is applied only if the chests are unchanged;
    otherwise the market is cleared again on the server thread.
- **Loading a site for a moment** (`SiteLoader`). What is decided as a record but needs blocks and
  entities is done live by holding a small area loaded (a ticket expiring after 30 s, renewed while
  needed): a search party founding its village, an army storming a player's base with the player
  away, a night agent at such a base.
- **Caravans** (`Caravan`, `Caravans`).
  - **Planning.** Every 200 ticks, each settled village with an elder and no caravan out plans a
    trade, at most every `world.caravan_interval_days` (2) days. A worker scores every village
    within `world.caravan_range` (1500 blocks) whose country trades with it (same country, peace
    or alliance). The score is the value of the home surpluses the other treasury lacks plus the
    other's surpluses home lacks, less a distance penalty.
  - **Cargo.** Up to 64 units per family and a purse of up to 48 emeralds (keeping 3 days of
    wages). The leader is a spare warrior, or else an adult whose trade another villager also
    practises.
  - **Abstract travel.** `world.caravan_blocks_per_hour` (120) blocks per in-game hour along a
    straight line.
  - **Live travel.** Near a player the leader is a real villager in the travel activity, walking
    to waypoints 20 blocks ahead. If it makes no progress for 20 s it is moved 12 blocks on.
    When it leaves entity-ticking chunks its entity data is stored in the caravan and the entity
    removed; it is spawned again from that data when the caravan reaches loaded chunks.
  - **Stand-ins.** A caravan that left an abstract village reaches loaded chunks before its
    leader's entity was ever loaded, so a stand-in with the leader's id and trade walks instead.
    If the original loads during the journey (its home chunk), it is dropped and the stand-in
    becomes the villager. Otherwise the stand-in is dropped when the caravan is home, since the
    original never left. A leader stored on the road that reaches an abstract home is spawned
    when the village loads.
  - **At the other treasury.** The caravan sells what that treasury lacks (below its reserve) and
    buys what home wants from its surplus, at the destination's market index. The relations
    markup is 1 + max(0, −score)/200. A side that cannot pay owes the rest as a treasury-to-
    treasury debt note, up to 32 emeralds per pair.
  - **Home.** Back home the cargo goes into the treasury. If the leader died, the cargo lies where
    it fell.
- **Debug.** `/btv world` lists ledgers, caravans and border disputes. `/btv world step` forces
  every village step, `/btv world caravan` plans caravans now, and `/btv world ledger` shows a
  village's ledger and treasury.
