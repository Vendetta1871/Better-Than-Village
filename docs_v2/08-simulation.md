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

- The old offline catch-up, old [06-society §7](../docs/independent-villagers/06-society.md), is
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

  Headless server tests ([`tools/servertest.py`](../tools/servertest.py)) use them to check
  phase "done" criteria.
