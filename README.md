# Better Than Village

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

## Documentation

- `docs_v2/` — the design: [overview and glossary](docs_v2/README.md),
  [roadmap](docs_v2/01-roadmap.md), professions, economy, land, law, politics, warfare and the
  abstract simulation. Each document ends with implementation notes.
- `docs/dev/ARCHITECTURE.md` — packages, tick flow and an API cheat sheet.
- `tests/` — headless server scenarios and their runner ([how to run](tests/README.md)).

## Features

- **Households and economy** — ten fixed professions passed on to children, home chests,
  gatherings with prices, taxes and wages, credit and debt notes, conscription of the idle poor,
  births paid by the parents, healers curing zombie villagers.
- **Land** — an irregular territory grown by construction, country domains split by chunk, plots
  and empty houses sold as deed maps, houses built to order on a player's plot, terraforming,
  doorways fixed for unreachable houses, roads cut through hills, demolition of illegal builds,
  layered walls with the elder choosing between houses and walls, lamp posts, search parties
  founding daughter villages.
- **Law** — offences seen by witnesses, warnings through vanilla gossip, war on the player,
  surrender with a white flag, peace treaties bought from the elder.
- **Countries and the abstract world** — capitals, loyalty and orders, diplomacy, border
  purchases; unloaded villages keep living through ledgers (trade, work, births, conscription,
  search parties), and caravans travel between them.
- **Warfare** — campaigns with outposts and sieges (abstract or live: soldiers break, tunnel or
  climb the walls ring by ring, riders jump them), conquest, war weariness and peace terms,
  warrior classes, night watch, cavalry on campaign, golems built from iron and taken to war,
  border watch, night operations (theft, arson, killing) and offensives and coalitions against a
  strong player's base, raided even while the player is away.

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

- `/btv status`, `/btv villagers`, `/btv offers` — colony dump, villager AI states, trade offers.
- `/btv jobs`, `/btv repair`, `/btv house <biome> [template] [floor]` — construction;
  `/btv path <pos>` — can the village walk there, and what is in the way.
- `/btv land [sell | illegal | grow <blocks>]` — territory and domain map, deed sales.
- `/btv country [list | join | secede | loyalty | order | relation | war | peace | border | day]`
  — countries and diplomacy.
- `/btv world [step | caravan | stock | ledger]` — the abstract world.
- `/btv war [launch | step | fortify | army | recruit | night | intel | offensive | player]` —
  campaigns, armies, night operations and war on the player.
- `/btv law …`, `/btv bot …` — law debugging and fake-player test bots.
- `/btv film …` — staged-demo tools. Use on a world copy.

## Differences from the original

- Houses use vanilla village templates; the original mod's custom house NBTs and worldgen
  overrides are not included.
- Mod id `better_than_village`, command `/btv`.

## License

Released under the [GNU General Public License v3.0](LICENSE) (GPL-3.0-only).
