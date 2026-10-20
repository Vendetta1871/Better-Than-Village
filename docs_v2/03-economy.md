# 03 — Economy

## Currency and money supply

The currency is the **emerald** (the vanilla item). Emeralds sit in real chests.

| | Source |
|---|---|
| Starting capital | Created once when a village is generated (the world existed before the player). Split between the treasury (≈ 20 %) and households (by profession, ± noise). Scales with the village's size. |
| Inflow | The player buys from villagers; the miner finds emerald ore (rare, mountains only). |
| Transfer | Trade between villages (caravans), country redistribution, tribute, conquest. These only move money; the world total does not change. |
| Outflow | The player sells to villagers. |

Nothing else creates or destroys emeralds. The **player** is the only real outflow, so the
risk is a player farm draining a village of all its money. Three safeguards keep it in check:

- **Reserve.** A villager never spends below a survival reserve (several days of food) when
  buying from the player.
- **Scarcity lowers prices.** A cash-poor village bids low, because prices react to available
  money (see Prices). Selling to it gets less attractive on its own.
- **Restock limits.** Vanilla restock limits (max uses per offer) cap the volume per day.

There are usually fewer emeralds than trade needs. **Credit** (below) fills the gap without
printing money.

## Credit and debt notes

When a buyer lacks emeralds, a trade can be settled **on credit**.

- **The note.** The debtor writes a **debt note**: a vanilla written book titled e.g.
  "Debt note", authored by the debtor. Its pages list the creditor, amount, interest, due day
  and collateral. The note sits in the creditor's chest. The debt itself is a server record, so
  copying or losing the book changes nothing.
- **Where credit is used:**
  - between villagers at gatherings (buying inputs, paying a mason for a child's house);
  - a household borrowing from the treasury;
  - between villages and countries: caravan trade, buying land, tribute.
- **Limits.**
  - **Per borrower:** a credit limit from their income over the last days, their wealth and their
    reputation (gossip).
  - **Per village:** total outstanding debt is capped at a multiple of the emeralds the village
    holds (default ×2), so credit cannot grow without bound.
- **Interest.** A rate per in-game day (default 1 %), adjusted by the lender's greed and the
  borrower's reputation.
- **Repayment.** At each gathering the debtor repays what is due, before buying anything else.
- **Default.** When a note is overdue:
  - **villager debtor:** `MINOR_NEGATIVE` gossip; then the elder confiscates goods at market
    value; a debtor whose trade still earns nothing is conscripted ([02](02-professions.md));
  - **village or country debtor:** relations worsen; prolonged default is a **cause of war**
    ([06](06-politics.md)). Wars over debts are one more way the villager population is kept in
    check.
- **Transfer.** A creditor may pay with a note they hold (assigning the debt to someone else)
  *(proposed, later)*.

### Credit with the player

Credit works both ways with the player, through the vanilla trade screen only.

**The player borrows** (buys on credit):
- **Signing.** A villager whose goods the player cannot afford may offer them **on credit**. The
  trade's cost is a **book and quill** (the player "signs"). The mod turns it into a debt note
  authored by the player and puts it in the villager's chest.
- **Limit.** Set by the player's gossip in the village and their repayment history. A player who
  has defaulted before gets no credit until the gossip fades.
- **Repayment.** Emeralds are taken automatically when the player sells to that village, or the
  player pays at any time through a "repay debt" offer with the creditor or the elder.
- **Default.** An overdue debt is an **offence** ([05](05-law.md)): a warning first, then war if
  it is still unpaid when the grace period ends (default 1 in-game day).

**The player lends** (sells on credit):
- **The note.** A villager or the treasury that lacks emeralds pays the player with a **debt
  note**. The player receives the written book, authored by the debtor.
- **The book matters here.** For notes the player holds, the book is a **bearer instrument**: it
  is needed to collect and can be handed to another player. Only the original counts (written
  book generation 0). The server marks a note as paid, so copies are worthless.
- **Collecting.** When the debtor has the money, they show a "note → emeralds" offer. The elder
  shows the same offer for treasury notes.
- **If the debtor defaults:**
  - **The village is the guarantor.** The treasury pays the player in the debtor's place.
  - **If the treasury cannot pay either,** the player may lawfully take goods from the debtor's
    home chest, up to the debt at market value. It is not theft ([05](05-law.md)). The amount
    taken is written off the note, and the note is paid once the full debt is covered.

## Households

- **What a household is.** One adult with a house (the bed they own), one or more **home
  chests** inside it, and a ledger. The ledger is kept on the server, keyed by villager UUID,
  and mirrors the chest contents. It is needed for the abstract simulation (see
  [08](08-simulation.md)).
- **What the chest holds.** Money, food, tools (means of production) and goods for sale.
- **Production.** What a villager produces goes into their own chest.
- **Consumption.** Villagers eat daily from their own chest. Tools wear out and are replaced
  by purchase. This reuses the current real tool cost.
- **Ownership.** House ownership is stored per building, as owner UUID or "village". Taking
  from a home chest is theft from that villager ([05](05-law.md)).

Replaces: the colony storage categories as the main stock (old
[01-core §6](../docs/independent-villagers/01-core.md)). The treasury below is the only shared
storage left.

## Gatherings (internal market)

- **When.** Twice a day at the bell, in the **morning** (after waking) and in the **evening**
  (before going home).
- **Attending.** Villagers walk to the bell and stay for the gathering window. Anyone not in
  range (working far away, in a mine) skips that round.
- **Orders.**
  - Each attendee posts **asks**: goods in their chest above their own needs, at their ask
    price.
  - Each attendee posts **bids**: goods they need (food for N days, tool replacements, inputs
    for their trade), at their bid price, capped by their cash.
- **Clearing.** Orders are matched best-price first; a trade happens at the midpoint of the
  ask and bid.
- **Hard constraints.** A villager cannot sell what is not in their chest and cannot buy without
  the money. Both are checked at clearing time.
- **Delivery.** Goods and emeralds move directly chest to chest (deliberate simplification).
- **Gossip.** Each trade adds a small `TRADING` gossip between the two villagers (vanilla
  gossip type).

### Prices

```
price(seller → buyer, item) = base(item)
                            × market(item)        // supply/demand in this village
                            × (1 + greed(seller)) // personality
                            × rep(buyer in seller's eyes)
```

| Factor | How it works |
|---|---|
| `base` | A config table of base values. |
| `market` | The ratio of bid volume to ask volume over the last few gatherings, smoothed and clamped to [0.25, 4]. A village with little cash produces low bid volume, so prices fall. |
| `greed` | A per-villager trait, about ±0.3, partly inherited ([02](02-professions.md)). |
| `rep` | Reputation from vanilla gossip. Good reputation means a discount, bad reputation a surcharge, about ±25 % at the extremes. |

Players get vanilla gossip discounts as well (see below).

## Player trade

- **Screen.** The vanilla merchant screen, with no new UI.
- **What a villager sells.** Offers are built from the villager's home chest: they sell goods
  above their needs and buy goods they need.
- **Prices.** Offers use the price formula with the player as the buyer. Vanilla gossip and Hero
  of the Village apply as usual.
- **Limits.** Buy offers are limited by the villager's cash above their reserve. Stock and cash
  are recomputed at each gathering (this replaces vanilla restock at the workstation).
- **Elder offers.** The elder sells deed maps, empty houses ([04](04-land.md)) and peace
  treaties ([05](05-law.md)), and pays out treasury debt notes.
- **Credit offers.** Signing a note, repaying, collecting (see "Credit with the player").

Reuses: the transaction primitive and merchant integration, old
[06-society §1.1–1.3](../docs/independent-villagers/06-society.md). The stock source changes from
colony storage to the villager's own chest.

## Taxes and treasury

- **Treasury.** The village stock: the warehouse the village placed by the bell and every
  village chest that is not a home chest.
- **Collection.** The elder collects taxes at the evening gathering:
  - **money tax**: a % of each household's income since the last collection (default 10 %);
  - **levy in kind**: a % of produced goods (default 5 %), for example food and building
    materials. It is taken when the worker delivers the goods home, so it never taxes the same
    stock twice.
- **Unpaid tax.** Becomes debt, and the gossip about the villager worsens. After a long time,
  goods are confiscated at market value.
- **What the treasury pays for:**
  - the elder's salary;
  - warriors' wages;
  - army gear, horses and potions;
  - repairs of damaged buildings;
  - military construction: walls, gates, outposts, upkeep of the village golem;
  - infrastructure: roads, tunnels, lamp posts, pit filling, demolishing illegal builds.
- **Paying for work.** The treasury pays masons, carpenters and miners for public work at market
  rates, plus the cost of materials.

## Births and housing

**Chosen model: the parents pay.** The treasury pays only to keep a village alive.

Why the parents pay:
- Wealth matters: rich professions have more children, so a village that sells well grows.
- Money flows to masons, carpenters and miners.
- Population is capped by the economy, not by a hard-coded limit.
- Together with inherited professions, it keeps the village's specialization.

If the treasury paid for houses instead, the tax rate would become the only population lever,
and households would not matter.

The flow:
1. **Candidates.** At each census, the village looks for a **candidate pair**. Any two adults
   qualify if both are willing in the vanilla sense (enough food), and their combined capital is
   at least the expected house cost plus a reserve. Credit counts toward that capital, within the
   limits above. There are no persistent marriages; the pair exists only for this birth.
2. **Profession.** The child's profession is chosen now, from one parent ([02](02-professions.md)),
   because it decides what the house comes with.
3. **Buying land.** The pair buys a **plot** from the elder; the money goes to the treasury.
4. **Building.** The pair **commissions a house with its workplace**, as one package:

   | Child's profession | Comes with the house |
   |---|---|
   | Farmer | a new field next to the plot |
   | Herder | a pen (and a stable for horse breeders) |
   | Smith | furnaces and the armorer's workstation |
   | Others | their vanilla workstation, plus anything the trade needs |

   Each parent pays in proportion to their capital. The money goes to the treasury, which
   builds the house as public work: it pays the mason for the labour and supplies the materials
   (deliberate simplification, see README).
5. **Birth.** The mason builds the house. When its bed is ready, the pair breeds the vanilla way,
   which already needs a free bed. The newborn claims the new bed and has a household from day
   one.
6. **Fallback.** If the village is below the minimum viable population, or a guaranteed
   profession is missing (no farmer, no warrior), the **treasury** pays for the house.

If there is no land left for a plot, no births happen. That pressure triggers expansion
([04](04-land.md)).

## Trade between villages

The elder of each village runs **caravans** to other villages that are not at war with it:
- **Buying.** They buy goods with unmet bids at home (goods nobody in the village produces).
- **Selling.** They sell the treasury's surplus and goods consigned by households for a
  commission *(proposed)*.
- **Prices.** They trade at the other village's gathering, using its prices.

Inside a country, the ruler can also order **redistribution** without payment
([06](06-politics.md)). Caravans are simulated abstractly unless a player is near
([08](08-simulation.md)).
