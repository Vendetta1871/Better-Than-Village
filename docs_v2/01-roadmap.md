# 01 — Roadmap

The fork is built in five phases. Each phase leaves a playable mod: later phases add systems,
they do not finish half-working ones. Each phase can be switched off in the config.

The current code base is a clean-room port of Independent Villagers (communal storage,
demand-based roles). Phase 1 is the largest refactor: it replaces communal ownership with
households.

---

## Phase 1 — Households and economy

**Goal:** every villager is an economic agent with a fixed profession, a house, a home chest and
money. Goods change hands only through trade or taxes.

Scope:
- New role set: ten professions plus children; demand-based job switching is removed
  ([02](02-professions.md)).
- Generation rules: a guaranteed roster (elder, warrior, farmer); vanilla professions are
  converted; starting emerald capital is handed out.
- Households: house and bed ownership, home chests, per-villager ledger.
- Production goes to the producer's own chest; tools, food and inputs are bought.
- Gatherings at the bell twice a day, with order matching and prices from supply, demand,
  personality and reputation ([03](03-economy.md)).
- Player trade: vanilla merchant offers built from the villager's own stock and cash.
- Treasury in the elder's house: taxes in money plus an in-kind levy; it pays the elder's salary
  and the warriors' wages.
- Credit between villagers, with the treasury and with the player: debt notes as written books,
  limits, interest, default handling ([03](03-economy.md)). A player's default becomes an offence
  in phase 3.
- Conscription of idle adults, capped by the treasury budget.
- Births: parents pay for a house, bundled with the workplace for the child's profession,
  before the child is born; the child inherits a parent's profession.
- New and changed jobs:
  - carpenter: processes wood and builds lamp posts;
  - herder: fishes when meat runs short;
  - miner: gets ore from stone;
  - smith, healer, librarian: new work tasks.

Replaces in the current code: `ColonyStorage` categories as the main stock, `RoleBalancer`, the
abstract market (old [06-society §1.4](../docs/independent-villagers/06-society.md)).

Milestones (each one builds, passes a server test and is committed on its own):

| # | Milestone | Status |
|---|---|---|
| 1.1 | Fixed professions: the ten professions, vanilla conversion, elder succession by reputation, inheritance, the herder's fishing fallback; `RoleBalancer` removed | done |
| 1.2 | Households: bed and house ownership, home chests, starting capital; the village stock that is left is the treasury | done |
| 1.3 | Household stock: work tasks take from and deliver to the worker's own chest; public work uses the treasury | done |
| 1.4 | Gatherings, prices and player trade from the villager's own stock; taxes and wages; the abstract market removed | done |
| 1.5 | Credit and debt notes; conscription and the idle poor | done (credit with the player untested without a client) |
| 1.6 | Births paid by the parents: plot, house and workplace, then the child | done |
| 1.7 | New work: carpenter processing and lamp posts, ore from stone, smith, healer and librarian tasks | done (zombie cures open) |

Done when: a generated village runs for 10+ in-game days on a headless server. Its emerald
total changes only through player trades, everyone eats, and births happen only when a house
was paid for.

## Phase 2 — Land

**Goal:** the village owns its territory, controls its domain and grows in layers.

Scope:
- Territory: noisy shape, grows with construction, has a maximum area.
- Domain: per-chunk ownership; at generation, the land is split between villages (Voronoi) so
  that domains touch but do not overlap ([04](04-land.md)).
- The elder sells plots and empty houses to the player as deed maps.
- Construction is commissioned by households (child houses with workplaces) and the treasury
  (walls, outposts, infrastructure).
- The miner terraforms within strict limits (flattening hills, digging tunnels, filling pits)
  and demolishes illegal player builds.
- Layered walls: new rings are added outside, old rings are kept and maintained.
- Expansion: when the territory is at its maximum, a search party founds a new village in the
  own domain or on unowned land.

Done when: a village fills its maximum territory, builds a second wall ring and founds a daughter
village, and an illegal player build in the territory is demolished.

## Phase 3 — Law and reputation

**Goal:** the player lives under the village's law.

Scope ([05](05-law.md)):
- Offences in the territory: weapon in hand, building or breaking blocks, killing animals,
  attacking villagers, theft.
- Witnesses, warnings with a 30–60 s grace period, and repeat offences tracked through vanilla
  gossip.
- War on the player, defensive version: every warrior of the country attacks the player on
  sight, and villagers refuse trade.
- Surrender gesture (white paper, banner or carpet) and peace treaty bought from the elder for
  a treaty book.

Done when: each offence produces a warning, then war, then surrender or peace in a server test.

## Phase 4 — Countries and the abstract world

**Goal:** villages form countries that act while nobody is watching.

Scope:
- The abstract simulation layer: economy, births, construction and travel of unloaded villages,
  time-sliced and with async computation on snapshots ([08](08-simulation.md)).
- Country model: capital, succession of the capital, ruler orders (resource redistribution,
  mobilization), loyalty, rebellion ([06](06-politics.md)).
- Elder caravans between villages (buying missing goods, selling surpluses).
- Border changes: buying border chunks, or being refused.
- Debts between villages and countries; default as a cause of war.
- Diplomacy states: alliance (common enemy or good trade), peace, hostility, war. War is
  declared here but fought in phase 5.

Done when: two countries trade by caravan with no player nearby, and a daughter village that
ignores orders is either tolerated or suppressed (the latter only once phase 5 exists).

## Phase 5 — Warfare

**Goal:** wars between villages and against the player are fought, not just declared.

Scope ([07](07-warfare.md)):
- Warrior classes (ranged, infantry, cavalry) with class changes as needed; horses from the
  herder's stables.
- The village iron golem (capped at one) in campaigns.
- Outposts: in the own domain for border watch, and in the enemy domain before every attack.
- Campaigns: mobilization, march, outpost, siege, resolution. Fought live near a player,
  computed abstractly otherwise.
- Sieges: breaking walls, tunnelling under them, building up to climb them.
- Conquest: survivors become citizens of the winner.
- War weariness: elders ask for peace after significant losses, not only after total defeat.
- Warrior shifts (day duty, night watch) and night operations: theft raids, arson, killing
  sleeping warriors.
- Offensive war against the player:
  - base detection from home blocks and presence;
  - a strength score that decides which countries join.

Done when: a country conquers a neighbour with no player nearby, and a live siege of a
two-ring village plays out near a player.
