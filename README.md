# Better Than Village

<!-- MEDIA hero (demo/hero.txt): the village from above, its wall along the outermost houses -->
![A village behind its wall](docs/media/hero.png)

NeoForge 1.21.1 mod that turns vanilla villages into small medieval societies. Villagers really doing their jobs, interact with environment (break and build blocks, catch and breed animals, grow trees and mine ores) and each other (trade and pay taxes, wage war and conquer each other). The player can interact with them, but never join a country.

No new blocks, items, GUIs or entities: everything goes through vanilla assets.

Started as a clean-room implementation of the Independent Villagers mod - it is still available on the 
`legacy` branch.

## What villages do

### Villagers work

<!-- MEDIA lumberjack (demo/lumberjack.txt): a lumberjack fells a tree log by log and replants it -->
![A lumberjack felling a tree](docs/media/lumberjack.gif)

Lumberjacks fell trees and plant saplings in their place, miners dig a mine, farmers till, sow and harvest the fields.

<!-- MEDIA herder (demo/herder.txt): the herder lures sheep into the pen with wheat and breeds them -->
![A herder breeding sheep in the pen](docs/media/herder.gif)

The herder lures animals, breeds the ones it has penned, shears the sheep and milks the cows for the village stock.

### A village is an economy

Each villager keeps a household. Farmers, miners and carpenters sell what they made. The elder collects taxes and
pays wages for public work. A child is born only  when its parents can pay for its house.

### Villages build themselves

<!-- MEDIA building (demo/building.txt): a mason finishing a house block by block -->
![A mason building a house](docs/media/building.gif)

Masons build new houses and walls with materials the village gathered. A village that runs out of land will found a daughter village.

<!-- MEDIA pit (demo/pit.txt): the miner closes a deep pit among the houses from its edge inward -->
![A miner filling a pit in the village](docs/media/pit.gif)

The village keeps its ground in order: miners close pits and dig a way into houses nobody can walk into.

### Land is property

The elder sells plots and empty houses, so a player can't build without his permission.

### The village has laws

Drawn weapons, building on village land, theft, killing livestock and hitting villagers are offences, seen by witnesses and remembered. A white flag in hand stops the fighting.

### Healers cure zombie villagers

Healer cures zombie villagers. Warriors fight it only when there is no healer or no cure.

### Countries go to war

<!-- MEDIA siege (demo/siege.txt): five against five, a rider among the attackers, no golems -->
![A siege](docs/media/siege.gif)

Countries trade by caravan, buy border land from each other, quarrel and make war. A conquered village joins the winner; long wars wear countries down until they sue for peace.

> **Work in progress:** the fighting itself (how soldiers and defenders close in, strike and fall)
> is not finished yet and looks rough.

At night, countries send thieves, arsonists and assassins to their enemies. A player who grows
too strong becomes a target too: neighbours form coalitions, find the player's base and raid it,
even while the player is away.

### The world lives on while nobody watches

Villages far from any player keep living through ledgers. When a player comes back, the village has the houses, people and walls it earned in the meantime.

## Documentation

- `docs_v2/`: the design. See the [overview and glossary](docs_v2/README.md) and the
  [roadmap](docs_v2/01-roadmap.md); there are documents for professions, economy, land, law,
  politics, warfare and the abstract simulation. Each document ends with implementation notes.
- `docs/dev/ARCHITECTURE.md`: packages, tick flow and an API cheat sheet.
- `tests/`: headless server scenarios and their runner ([how to run](tests/README.md)).
- `demo/`: the staged scenes the pictures above come from, replayed by the dev client
  ([how to run](demo/README.md)).

Every subsystem has a section with an `enabled` switch in `config/better_than_village.json`
(created on first start).

## Build

Requires JDK 21.

```sh
./gradlew build   # jar in build/libs/
```

## Commands

All commands are for operators (permission level 2) and mostly serve debugging and tests; run
them at or near a village (`execute positioned <x> <y> <z> run btv …` from the console).

- `/btv status`, `/btv villagers`, `/btv offers`: colony dump, villager AI states, trade offers.
- `/btv jobs`, `/btv repair`, `/btv house <biome> [template] [floor]`: construction.
  `/btv path <pos>`: can the village walk there, and what is in the way.
- `/btv land [sell | illegal | grow <blocks>]`: territory and domain map, deed sales.
- `/btv country [list | join | secede | loyalty | order | relation | war | peace | border | day]`:
  countries and diplomacy.
- `/btv world [step | caravan | stock | ledger]`: the abstract world.
- `/btv war [launch | step | fortify | ring | unwall | army | recruit | horses | golems | garrison | night | intel | offensive | player]`:
  campaigns, armies, night operations and war on the player.
- `/btv law …`, `/btv bot …`: law debugging and fake-player test bots.
- `/btv film …`: staged-demo tools. Use on a world copy.

## Differences from the original

- Houses use vanilla village templates; the original mod's custom house NBTs and worldgen
  overrides are not included.
- Mod id `better_than_village`, command `/btv`.

## License

Released under the [GNU General Public License v3.0](LICENSE) (GPL-3.0-only).
