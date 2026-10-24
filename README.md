# Better Than Village

<!-- MEDIA hero (demo/hero.txt): the village from above, its wall along the outermost houses -->
![A village behind its wall](docs/media/hero.png)

NeoForge 1.21.1 mod that turns vanilla villages into small medieval societies. Every villager is
a household with a house, a chest, money and a trade fixed for life. Villagers trade at gatherings
by the bell, lend with signed debt notes, pay taxes to the elder, build and wall their villages and
found daughter villages when they run out of land. Villages of the same people form countries that
trade by caravan, quarrel, send thieves at night, rebel, wage war and conquer each other. The
player is always an outsider: they can trade, buy land, break the law, fight a war or buy peace,
but never join a country.

No new blocks, items, GUIs or entities: everything goes through vanilla trade screens, filled
maps (land deeds), written books (peace treaties, debt notes), professions and gossip.

Started as a clean-room reimplementation of the Independent Villagers mod; the port as it was
before the fork lives on the `legacy` branch.

## What villages do

### Villagers work

<!-- MEDIA lumberjack (demo/lumberjack.txt): a lumberjack fells a tree log by log and replants it -->
![A lumberjack felling a tree](docs/media/lumberjack.gif)

Lumberjacks fell trees from the top down and plant saplings in their place, miners dig a
staircase mine and follow the ore, farmers till, sow and harvest the fields around the village.

<!-- MEDIA herder (demo/herder.txt): the herder lures sheep into the pen with wheat and breeds them -->
![A herder breeding sheep in the pen](docs/media/herder.gif)

The herder fences a pen, lures sheep, cows and goats into it with wheat, breeds the ones it has
penned before it brings more, shears the sheep and milks the cows for the village stock.

### A village is an economy

Each villager keeps a household: a bed, a home chest, emeralds and one of ten trades for life,
passed on to its children. Every morning the village meets at the bell. Farmers, miners and
carpenters sell what they made, prices follow supply and demand, the elder collects taxes and
pays wages for public work. Villagers lend each other money against signed debt notes; the idle
poor are called up as warriors. A child is born only when its parents can pay for its house.

### Villages build themselves

<!-- MEDIA building (demo/building.txt): a mason finishing a house block by block -->
![A mason building a house](docs/media/building.gif)

Masons build new houses from the vanilla templates of the village's biome, block by block, with
materials the village gathered. The territory grows with every house. Each morning the elder
decides what comes first: walls in times of danger, houses that parents or a player paid for
otherwise. Roads cut through hills and tunnel under them, and a village that runs out of land
sends a search party to found a daughter village.

<!-- MEDIA pit (demo/pit.txt): the miner closes a deep pit among the houses from its edge inward -->
![A miner filling a pit in the village](docs/media/pit.gif)

The village keeps its ground in order: miners close pits and craters among the houses with a
lid of earth and stone of the treasury, laid from the edge inward, dig a way into houses nobody can walk into and keep their own mine
outside the houses.

The wall runs a few blocks outside the outermost houses, following the outline of the village,
with the fields outside as in a medieval town. When the village outgrows it, a new ring goes up
outside and the old one stays. Gates stand where roads cross the rings.

### Land is property

The elder sells plots and empty houses as deed maps and, for a fee, has the masons build a house
on a player's plot. Villagers keep off player land, and a player who builds on village land
without a deed sees the build torn down.

### The village has laws

Drawn weapons, building on village land, theft, killing livestock and hitting villagers are
offences, seen by witnesses and remembered through vanilla gossip. The first offence earns a
warning, the next one war with the whole country. A white flag in hand stops the fighting; peace
is a written treaty bought from the elder.

### Healers cure zombie villagers

A villager who sees a zombie villager calls the healer, who throws a potion of weakness and cures
it with a golden apple on the spot. Warriors fight it only when there is no healer or no cure.

### Countries go to war

<!-- MEDIA siege (demo/siege.txt): five against five, a rider among the attackers, no golems -->
![A siege](docs/media/siege.gif)

Villages of one people form a country with a capital, loyalty and orders. Countries trade by
caravan, buy border land from each other, quarrel and make war. An army marches out with riders
and the village's iron golem, sets up an outpost and lays siege. The army storms the walls ring
by ring at one breach: a gate it opens, or a wall broken, climbed or tunnelled under. The
villagers hide in their houses, and the defenders gather inside the wall at the breach to meet
it. A conquered village joins the winner; long wars wear countries down until they sue for peace.

> **Work in progress:** the fighting itself (how soldiers and defenders close in, strike and fall)
> is not finished yet and looks rough.

At night, countries send thieves, arsonists and assassins to their enemies. A player who grows
too strong becomes a target too: neighbours form coalitions, find the player's base and raid it,
even while the player is away.

### The world lives on while nobody watches

Villages far from any player keep living through ledgers: they trade, work, build, have children,
send caravans, search for new land and fight abstract battles. When a player comes back, the
village has the houses, people and walls it earned in the meantime.

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
