# 06 — Politics

## Countries

- **At generation**, every village is its own country, and its own capital.
- **New villages.** Villages founded by a country's search party join that country.
- **Conquest.** Conquered villages join the conqueror.
- **The capital** is the village the country comes from. If the capital is lost (conquered or
  destroyed), the next oldest village of the country, by founding order, becomes the capital.
  A country with no villages left is gone.
- **The ruler** is the elder of the capital. They decide on war, peace, alliances and orders.
- **The player** never belongs to a country.

## Orders

The ruler can issue orders to the other villages of the country:
- **Redistribution.** Move resources from one village's treasury to another's, without
  payment: food in a famine, materials for walls, emeralds. Only treasuries are affected,
  never household chests. Delivered by caravan.
- **Mobilization.** Send warriors (and the village golem) to a gathering point for a campaign
  ([07](07-warfare.md)).
- **Higher taxes, temporarily.** A share of each village's treasury goes to the capital.

## Loyalty and rebellion

Each non-capital village has a **loyalty** score.

| Lowered by | Raised by |
|---|---|
| Distance from the capital | Shared victories |
| Resources taken by orders | Redistribution received |
| Losses in the country's wars | Long peace |
| Conquest (conquered villages start low) | Time since joining |

- **Disobedience.** Below a threshold, the village's elder may **ignore orders**. Each ignored
  order lowers the ruler's patience.
- **The ruler's response:**
  - **tolerate**: patience slowly recovers; or
  - **suppress**: a punitive campaign against the village.
- **Outcome of suppression.**
  - If the ruler wins, the rebel village is "conquered" back with low loyalty.
  - If the rebels win, the village becomes an **independent country** with itself as capital.

## Diplomacy

Relations between two countries:

| State | Meaning |
|---|---|
| **Alliance** | Common enemy or good trade (see below). |
| **Peace** | The default. Theft raids are still possible against countries with bad relations. |
| **Hostility** | Relations are terrible, but war has not been declared. Sabotage (arson, killing) is allowed. It is the step before war; a treaty or improving relations can bring it back to peace. |
| **War** | Open campaigns ([07](07-warfare.md)). |

Relations are a score between the two countries, fed by trade, debts, incidents and the gossip
their villagers hold about each other's villagers.

- **Alliances** form when two countries **share an enemy** or have **good trade**: steady
  caravan trade and no defaulted debts between them. They are recorded as a treaty, abstract. An
  ally is expected to join defensive wars. Refusing lowers relations, and if relations fall far
  enough the alliance breaks.
- **Causes of war:**
  1. **Defending an ally.** An ally was attacked.
  2. **Repelling an attack.** The country itself was attacked.
  3. **Conquest.**
     - **Resources and people**, for example a country with no miners that cannot buy ore.
     - **Territory**: a country with no free land to expand into.
  4. **Border dispute.** A country needs land for a search party, and the owner refused to sell
     it or the buyer could not pay.
  5. **Unpaid debt.** A village or country has defaulted on a debt note to this country
     ([03](03-economy.md)).
  6. **Repeated incidents.** Agents of another country were caught again and again
     ([07](07-warfare.md)). A single caught thief is answered with **revenge** at night, not with
     an immediate war.
  7. **Rebellion.** Suppressing a rebel village of its own.
  8. **Player offences** ([05](05-law.md)).
- **Deciding on war.** The ruler compares strength:
  - warriors, gear tier, the golem, wall rings and treasury on each side;
  - allies who would join.

  The ruler declares a conquest war only with a clear advantage.
- **Peace between countries.**
  - **When.** A side asks for peace when its elders have **had enough**: war weariness
    ([07](07-warfare.md)), not necessarily the loss of the whole army.
  - **Terms**, negotiated abstractly:
    - tribute (a one-time payment, or a share of taxes for N days);
    - transfer of border chunks or whole villages;
    - writing off debts;
    - a plain truce.

## Border changes

Domains belong to countries and change only by treaty or war ([04](04-land.md)). A typical case
is a search party that wants land inside a foreign domain.
1. **Offer.** The country offers to buy the border chunks, in emeralds or debt notes. The price
   is based on the chunk count and the owner's land value.
2. **Decision.** The owner **accepts or refuses**, even if the price can be paid. The decision
   depends on relations, strength and how much free land the owner has left.
3. **Accepted.** The chunks change owner.
4. **Refused, or no money.** The buyer may go to war over the land, or wait.

## Conquest

When a village's warriors are all destroyed and the attacker holds its bell area:
- **People.** Surviving villagers become **citizens of the winning country**. They keep their
  houses, chests and professions. This is why wars over resources make sense: a country without
  miners can take some.
- **Treasury.** It goes to the winner as loot. Debt notes the village held now pay the winner.
- **Domain.** The village's share of the domain goes to the winner.
- **Elder.** The old elder is deposed. The adult with the best reputation becomes the new elder,
  under the new ruler.
- **Loyalty** starts low.
- **Walls.** Rings damaged in the siege are repaired by the new owner's masons.

## Implementation notes (phase 4)

The `politics` package.
- **Countries** (`Country`, `Countries`).
  - Every village has a founding index; the capital is the country's oldest village. When the
    capital leaves the country or disappears, the next oldest takes over.
  - The record holds the ruler's patience and a temporary tax.
  - Country strength, per village: 4 per warrior, 3 per wall ring, 4 for a golem near a live
    village, 1 per 16 treasury emeralds and half the population. The law's treaty prices use it
    too.
- **Daily step** (`Politics`, run by the world scheduler on every new day):
  - **Loyalty.** A non-capital village loses up to 1.5 a day for each 400 blocks beyond 256 from
    the capital. It gains 0.5 a day of peace and up to 0.5 for time since joining. An order's
    caravan costs the giving village a quarter of the delivered value (at most 15) and gives the
    same to the receiving village. A daughter village starts at `politics.daughter_loyalty` (80).
  - **Orders** (`Orders`).
    - In a famine (treasury food under half its reserve) the best-stocked village sends food.
    - A capital with less than 3 days of wages taxes the other villages 20 % of their treasury
      emeralds for 3 days.
    - A war mobilizes the warriors of every village; this is only recorded until phase 5.
    - Obeyed orders travel by caravan. A village below `politics.disobedience_loyalty` (30)
      ignores them, which costs the ruler `politics.patience_per_ignored_order` (25) patience.
      Patience recovers 5 a day.
    - When patience runs out the ruler would suppress the village. Campaigns come in phase 5, so
      for now the ruler tolerates it and the patience starts over at 50.
  - **Diplomacy** (`Diplomacy`, `Relation`).
    - Scores decay 2 % a day. A caravan trade gives +2; an overdue treasury debt between
      countries gives −10, and its default (2 days later) −30. A refused border sale gives −5.
    - Peace becomes hostility at `politics.hostility_score` (−40) and returns at −20.
    - An alliance needs `politics.alliance_score` (50), 3 trade days in the last 10 and no
      defaults, or a common enemy. It ends below 20.
    - The ruler declares at most one war a day, for: a defaulted debt; a border dispute; an ally
      attacked by that country; incidents (hostility at −80); or resources (hostility, and its
      country has no miner while the other has).
    - The ruler declares only if its strength (plus half of its allies') is at least the
      target's plus its allies', or `politics.conquest_advantage` (1.5) times that for resources.
      Declining to defend an ally costs 30 with that ally.
    - A war ends with a plain truce (`politics.truce_days`, 5) when its cause is gone, or after
      `politics.war_max_days` (10) days, since nothing is fought before phase 5. A peace also
      settles the border dispute.
- **Borders** (`Borders`). A search party that finds no site in its own domain or on free land
  asks to buy the chunks within the largest territory around a site in a foreign domain.
  - **Price.** `politics.border_price_per_chunk` (0.15 emeralds) per chunk, up to 4 times that close to the
    owner's villages.
  - **Decision.** The owner refuses during war or hostility, with relations under −10 (under −30
    if the buyer is twice as strong), and for land within two largest-territory radii of its own
    villages.
  - **Payment.** The buyer pays the owner's capital, at least half in emeralds; the rest is a
    debt note.
  - **Refusal.** A refusal, or a buyer who cannot pay, is kept as a border dispute and the buyer
    waits 3 days.
- **Debug.** `/btv country` shows the country of the nearest village. Subcommands:
  - `list` shows every country;
  - `join <pos> <loyalty>`, `secede` and `loyalty <n>` change a village's country or loyalty;
  - `order <kind> <amount>`, `relation <pos> <score>`, `war <pos> <cause>` and `peace <pos>`
    stage orders and diplomacy;
  - `border <pos>` makes a border offer;
  - `day` runs the daily step now.
