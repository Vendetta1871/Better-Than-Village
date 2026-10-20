# Better Than Village — design v2

This folder is the design of **our fork**: what Better Than Village should become. It replaces
the Independent Villagers behavioral reference (`../docs/independent-villagers/`) as the source
of truth for *what* we build. The old reference stays in the repository: many mechanics
(gathering, navigation, construction, combat) are reused. Every document here links to the old
sections it builds on.

## Branches

| Branch | Contents |
|---|---|
| `legacy` | The Independent Villagers port as it was before the fork, with its docs (`docs/`) only. Frozen. |
| `main` | The fork: new code and this design (`docs_v2/`). Once the fork is implemented, `docs/` is removed from `main`; the old reference then lives only on `legacy`. |

## Vision

Vanilla villages become small medieval societies. Every villager is a **household**: they own
a house, a chest, money and the goods they produce. Professions are **fixed for life** and passed
on to children, so each village keeps its specialization. Villagers trade with each other at
**gatherings** by the bell, lend to each other with signed **debt notes**, pay **taxes** to the
elder and live by **laws** that also apply to
the player. Villages grow until they run out of land. Then they send out **search parties** and
found new villages. Villages founded by the same people form a **country**. Countries trade,
redistribute resources, send thieves and saboteurs at night, rebel, wage **wars** and conquer
each other. The player is always an
**outsider**. They can trade, buy land, break the law, start a war, lose a war or buy peace,
but never join a country.

## Design principles

1. **Mechanics only.** We add no new blocks, items, GUIs, effects or entity types. Everything is
   expressed through vanilla:
   - the merchant trade screen (buying land, houses, peace);
   - filled maps with decorations (land deeds, marked with red X like treasure maps);
   - written books (peace treaties, debt notes);
   - vanilla professions (villager looks);
   - vanilla gossip (reputation).

   Lang files and chat messages are allowed.
2. **Nothing from nothing.** Goods are produced, bought, sold and consumed for real. Every
   deliberate exception is listed under "Deliberate simplifications" below.
3. **Simulate abstractly when nobody watches.** Minecraft only ticks loaded chunks. Most
   inter-village activity (caravans, armies, search parties, unloaded economies) is computed
   as numbers and becomes real entities only near a player. See
   [08-simulation.md](08-simulation.md).
4. **Everything big is a config toggle.** Each subsystem can be switched off and tuned.
5. **The player is always external.** They are never a citizen, soldier, elder or ally of a
   country.

## Documents

| File | Topic |
|---|---|
| [01-roadmap.md](01-roadmap.md) | Implementation phases, scope and done criteria |
| [02-professions.md](02-professions.md) | Ten professions, vanilla looks, inheritance, children, the army as fallback |
| [03-economy.md](03-economy.md) | Emeralds, debt notes, households, gatherings, prices, player trade, taxes, treasury, births and housing |
| [04-land.md](04-land.md) | Territory and domain, plots and deed maps, construction, terraforming, layered walls, lighting, expansion |
| [05-law.md](05-law.md) | Laws, warnings, vanilla gossip, theft, war against the player, surrender, peace treaties |
| [06-politics.md](06-politics.md) | Countries, capitals, orders, loyalty and rebellion, diplomacy, conquest |
| [07-warfare.md](07-warfare.md) | Warrior classes and shifts, golems, outposts, campaigns, sieges, night operations, player strength, raids |
| [08-simulation.md](08-simulation.md) | Live vs abstract simulation, ledgers, materialization, performance |

## Glossary

| Term | Meaning |
|---|---|
| Village | A colony around a bell (old docs: "colony"). |
| Country | A set of villages ruled from one capital. At world generation every village is its own country. |
| Capital | The village the country comes from. If it is lost, the next oldest village takes over. |
| Territory | The irregular built-up core of a village. Village property; laws apply here. |
| Domain | The land a country controls. At generation it is split between villages so that domains touch but never overlap. The player is free here; it is defended only against other countries. |
| Household | One adult villager with their house, home chest(s), money and goods. |
| Home chest | The chest(s) inside a villager's house. They hold money, food, tools and goods for sale. |
| Treasury | The village stock: the warehouse the village placed by the bell and every village chest that is not a home chest. It holds tax money and the in-kind levy. |
| Gathering | The morning or evening market by the bell, where villagers trade with each other. |
| Plot | Land inside the territory that the player has bought. The player builds freely on it. |
| Deed map | A vanilla filled map that marks a bought plot or house with red X markers. It works as a receipt; ownership is stored on the server. |
| Treaty book | A vanilla written book with the terms of a peace treaty. It is a receipt and collectible only. |
| Debt note | A vanilla written book signed by a debtor, kept by the creditor. The debt itself is stored on the server. Notes the player holds are bearer instruments: the original book is needed to collect. |
| Hostility | Relations between countries that are terrible but not yet war. Sabotage is allowed. |
| Conscript | An idle adult serving in the army for a while. They keep their trade. |
| Night operation | A covert night mission against another country or the player: a theft raid or sabotage. |
| Illegal build | Player blocks in a village territory outside the player's own plots. Villagers demolish them. |
| Search party | Villagers who leave an overcrowded village to found a new one. |
| Outpost | A small fortified camp in a village's own domain or in an enemy domain before an attack. |
| Live / abstract | Whether a thing is simulated with real entities and blocks or as numbers (see 08). |

## Deliberate simplifications

- **Starting money and kits.** Villages start with emeralds already in chests, and every
  household with the tools, inputs and a few days of food of its trade: the world existed before
  the player arrived.
- **Children are fed by the village.** Children eat from the treasury; their parents do not buy
  for them.
- **Houses are bought from the treasury.** The parents of a child pay the treasury for the plot
  and the house; the treasury builds it as public work (it pays the mason and supplies the
  materials) instead of the parents hiring the mason and buying materials themselves.
- **Lamp posts and workshops at home.** Processing trades (carpenter, smith, librarian, healer)
  work at their home chest rather than at their workstation.
- **Ore from stone.** Miners get ores from ordinary stone with a configurable chance. Otherwise
  they would strip every vein near the village and the economy would stall.
- **Potions without Nether ingredients.** Healers brew potions without Nether ingredients.
  They still buy those ingredients when they are offered, so the goods have a market.
- **Instant market delivery.** Goods traded at a gathering move straight from chest to chest.
  Villagers attend the gathering, but they do not carry goods by hand.

## Configuration

Every phase adds a top-level config section with an `enabled` switch. The planned toggles are
`economy`, `land.terraforming`, `land.layered_walls`, `law`, `politics`, `warfare`,
`warfare.sieges`, `warfare.cavalry` and `simulation.abstract`. Numbers given in these documents
are proposed defaults; they all go into the config.

## Open questions

None at the moment. Smaller details marked *(proposed)* in the documents use the stated default
until decided otherwise.
